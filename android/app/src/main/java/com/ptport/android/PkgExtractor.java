// SPDX-License-Identifier: LGPL-3.0-or-later
// Reads the three archives the game needs (chunk1.psarc, texture.qar, pathid_list_ps4.bin) out of a fake PKG made from a
// P.T. dump. The package and PFS formats follow LibOrbisPkg (Maxton, LGPL-3.0-or-later): PKG/PkgReader.cs, PKG/Pkg.cs
// (GetEkpfs), PFS/PfsReader.cs, PFS/XtsDecryptReader.cs and PFS/PFSCReader.cs, with the two read fixes the PC installer
// applies (tools/patch_liborbis_readers.py). The PC installer does the same in C# (installer/Extractor/Program.cs).
package com.ptport.android;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class PkgExtractor {
    static final String[] REQUIRED = {"chunk1.psarc", "texture.qar", "pathid_list_ps4.bin"};

    static final String RETAIL_MESSAGE = "This PKG is a retail (PlayStation Store) package, or it is damaged. Retail packages are "
            + "encrypted for the console that owns the license and cannot be opened here. Dump P.T. from your own PS4 and make a "
            + "fake PKG (fPKG) from the dump.";
    static final String NOT_PT_MESSAGE = "This package does not hold P.T.'s game data.";

    interface Progress {
        void update(String step, long done, long total);
    }

    interface Source {
        void read(long offset, byte[] buffer, int position, int length) throws IOException;
    }

    private PkgExtractor() {}

    // ---------------------------------------------------------------------------------------------------------------------

    static final class ChannelSource implements Source {
        private final FileChannel channel;
        private final long base, size;

        ChannelSource(FileChannel channel, long base, long size) {
            this.channel = channel;
            this.base = base;
            this.size = size;
        }

        @Override
        public void read(long offset, byte[] buffer, int position, int length) throws IOException {
            if (offset < 0 || offset + length > size) throw new IOException("read past the end of the package");
            ByteBuffer target = ByteBuffer.wrap(buffer, position, length);
            long at = base + offset;
            while (target.hasRemaining()) {
                int n = channel.read(target, at);
                if (n < 0) throw new IOException("the package is truncated");
                at += n;
            }
        }
    }

    /** AES-XTS-128 over 0x1000-byte sectors, the tweak being the sector number (XtsDecryptReader.cs) */
    static final class XtsSource implements Source {
        private static final int SECTOR = 0x1000;
        private final Source source;
        private final long startSector;
        private final Cipher data, tweak;
        private final byte[] sector = new byte[SECTOR];
        private final byte[] tweaks = new byte[SECTOR];
        private long cached = -1;

        XtsSource(Source source, byte[] dataKey, byte[] tweakKey, long startSector) throws GeneralSecurityException {
            this.source = source;
            this.startSector = startSector;
            data = Cipher.getInstance("AES/ECB/NoPadding");
            data.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dataKey, "AES"));
            tweak = Cipher.getInstance("AES/ECB/NoPadding");
            tweak.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(tweakKey, "AES"));
        }

        private void load(long index) throws IOException {
            if (cached == index) return;
            source.read(index * SECTOR, sector, 0, SECTOR);
            if (index >= startSector) {
                try {
                    byte[] t = new byte[16];
                    ByteBuffer.wrap(t).order(ByteOrder.LITTLE_ENDIAN).putLong(index);
                    t = tweak.doFinal(t);
                    for (int block = 0; block < SECTOR; block += 16) {
                        System.arraycopy(t, 0, tweaks, block, 16);
                        int carry = 0;
                        for (int k = 0; k < 16; ++k) {
                            int b = t[k] & 0xFF;
                            t[k] = (byte) ((b << 1) | carry);
                            carry = b >>> 7;
                        }
                        if (carry != 0) t[0] ^= (byte) 0x87;
                    }
                    for (int i = 0; i < SECTOR; ++i) sector[i] ^= tweaks[i];
                    data.doFinal(sector, 0, SECTOR, sector, 0);
                    for (int i = 0; i < SECTOR; ++i) sector[i] ^= tweaks[i];
                } catch (GeneralSecurityException e) {
                    throw new IOException(e);
                }
            }
            cached = index;
        }

        @Override
        public void read(long offset, byte[] buffer, int position, int length) throws IOException {
            while (length > 0) {
                long index = offset / SECTOR;
                int within = (int) (offset % SECTOR);
                int count = Math.min(length, SECTOR - within);
                load(index);
                System.arraycopy(sector, within, buffer, position, count);
                offset += count;
                position += count;
                length -= count;
            }
        }
    }

    /** PFS image (PfsReader.cs): inodes, directories and the files' blocks */
    static final class Pfs {
        final Source source;
        final int blockSize;
        final boolean signed;
        final List<long[]> inodes = new ArrayList<>(); // size, blocks, db[12], ib[5]

        Pfs(Source raw, byte[] ekpfs, long pfsFlags) throws IOException, GeneralSecurityException {
            byte[] header = new byte[0x400];
            raw.read(0, header, 0, header.length);
            ByteBuffer h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            if (h.getLong(0) != 1 || h.getLong(8) != 20130315) throw new IOException("not a PFS image");
            int mode = h.getShort(0x1C) & 0xFFFF;
            blockSize = h.getInt(0x20);
            long inodeCount = h.getLong(0x30), inodeBlocks = h.getLong(0x40);
            byte[] seed = Arrays.copyOfRange(header, 0x370, 0x380);
            Source src = raw;
            if ((mode & 4) != 0) {
                byte[] base = ekpfs;
                if ((pfsFlags & 0x2000000000000000L) != 0) base = hmac(ekpfs, seed);
                byte[] input = new byte[4 + 16];
                ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN).putInt(1);
                System.arraycopy(seed, 0, input, 4, 16);
                byte[] key = hmac(base, input);
                src = new XtsSource(raw, Arrays.copyOfRange(key, 16, 32), Arrays.copyOfRange(key, 0, 16), blockSize / 0x1000);
            }
            source = src;
            signed = (mode & 1) != 0;
            int inodeSize = signed ? 0x2C8 : 0xA8;
            byte[] block = new byte[blockSize];
            for (long b = 0; b < inodeBlocks; ++b) {
                source.read((long) blockSize * (1 + b), block, 0, blockSize);
                ByteBuffer bb = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN);
                for (int j = 0; j < blockSize / inodeSize && inodes.size() < inodeCount; ++j) {
                    int o = j * inodeSize;
                    long[] node = new long[2 + 12 + 5];
                    node[0] = bb.getLong(o + 8);
                    node[1] = bb.getInt(o + 0x60) & 0xFFFFFFFFL;
                    for (int i = 0; i < 12; ++i) node[2 + i] = signed ? bb.getInt(o + 0x64 + 36 * i + 32) : bb.getInt(o + 0x64 + 4 * i);
                    for (int i = 0; i < 5; ++i) node[14 + i] = signed ? bb.getInt(o + 0x64 + 432 + 36 * i + 32) : bb.getInt(o + 0x94 + 4 * i);
                    inodes.add(node);
                }
            }
        }

        /** name -> {type, inode} */
        Map<String, long[]> list(int inode) throws IOException {
            long[] node = inodes.get(inode);
            Map<String, long[]> out = new HashMap<>();
            byte[] block = new byte[blockSize];
            for (long x = node[2]; x < node[2] + node[1]; ++x) {
                source.read(blockSize * x, block, 0, blockSize);
                ByteBuffer bb = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN);
                int p = 0;
                while (p + 16 <= blockSize) {
                    int number = bb.getInt(p), type = bb.getInt(p + 4), nameLength = bb.getInt(p + 8), entrySize = bb.getInt(p + 12);
                    if (entrySize == 0) break;
                    String name = new String(block, p + 16, nameLength, StandardCharsets.US_ASCII);
                    out.put(name, new long[] {type, number});
                    p += entrySize;
                }
            }
            return out;
        }

        void walk(int inode, String path, Map<String, Integer> files) throws IOException {
            for (Map.Entry<String, long[]> e : list(inode).entrySet()) {
                long type = e.getValue()[0];
                int number = (int) e.getValue()[1];
                if (type == 2) files.put(path + "/" + e.getKey(), number);
                else if (type == 3) walk(number, path + "/" + e.getKey(), files);
            }
        }

        PfsFile file(int inode) throws IOException {
            return new PfsFile(this, inode);
        }

        private int readInt(long offset) throws IOException {
            byte[] b = new byte[4];
            source.read(offset, b, 0, 4);
            return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt();
        }

        /** the blocks of a file that is not contiguous (signed images only), or null */
        int[] blocks(int inode) throws IOException {
            long[] node = inodes.get(inode);
            long count = node[1];
            if (count <= 1 || node[3] == -1) return null;
            int[] blocks = new int[(int) count];
            int n = 0;
            for (int i = 0; i < 12 && i < count; ++i) blocks[n++] = (int) node[2 + i];
            long sigsPerBlock = blockSize / 36;
            long remaining = count - 12;
            for (int i = 0; i < remaining && i < sigsPerBlock; ++i) blocks[n++] = readInt(node[14] * blockSize + i * 36L + 32);
            remaining -= sigsPerBlock;
            for (int j = 0; j * sigsPerBlock < remaining; ++j) {
                int indirect = readInt(node[15] * blockSize + j * 36L + 32);
                for (int i = 0; i < sigsPerBlock && i + j * sigsPerBlock < remaining; ++i) blocks[n++] = readInt((long) indirect * blockSize + i * 36L + 32);
            }
            for (int i = 1; i < blocks.length; ++i) {
                if (blocks[i - 1] + 1 != blocks[i]) return blocks;
            }
            return null;
        }
    }

    static final class PfsFile implements Source {
        final Pfs pfs;
        final long size, start;
        final int[] blocks;

        PfsFile(Pfs pfs, int inode) throws IOException {
            this.pfs = pfs;
            long[] node = pfs.inodes.get(inode);
            size = node[0];
            start = node[2] * pfs.blockSize;
            blocks = pfs.blocks(inode);
        }

        @Override
        public void read(long offset, byte[] buffer, int position, int length) throws IOException {
            if (blocks == null) {
                pfs.source.read(start + offset, buffer, position, length);
                return;
            }
            while (length > 0) {
                int index = (int) (offset / pfs.blockSize);
                int within = (int) (offset % pfs.blockSize);
                int count = Math.min(length, pfs.blockSize - within);
                pfs.source.read((long) blocks[index] * pfs.blockSize + within, buffer, position, count);
                offset += count;
                position += count;
                length -= count;
            }
        }
    }

    /** the compressed PFS container around the inner image (PFSCReader.cs) */
    static final class PfscSource implements Source {
        private final Source source;
        private final int blockSize;
        private final long[] map;
        private final byte[] sector, packed;
        private int cached = -1;
        private final Inflater inflater = new Inflater(true);

        PfscSource(Source source) throws IOException {
            this.source = source;
            byte[] header = new byte[0x30];
            source.read(0, header, 0, header.length);
            ByteBuffer h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            if (h.getInt(0) != 0x43534650) throw new IOException("not a PFSC image");
            blockSize = h.getInt(0xC);
            long blockSize2 = h.getLong(0x10), offsets = h.getLong(0x18), dataLength = h.getLong(0x28);
            if (blockSize != blockSize2) throw new IOException("PFSC block size mismatch");
            int count = (int) (dataLength / blockSize2);
            byte[] raw = new byte[8 * (count + 1)];
            source.read(offsets, raw, 0, raw.length);
            map = new long[count + 1];
            ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asLongBuffer().get(map);
            sector = new byte[blockSize];
            packed = new byte[blockSize];
        }

        private void load(int index) throws IOException {
            if (cached == index) return;
            long offset = map[index], length = map[index + 1] - offset;
            if (length == blockSize) {
                source.read(offset, sector, 0, blockSize);
            } else if (length > blockSize) {
                Arrays.fill(sector, (byte) 0);
            } else {
                source.read(offset + 2, packed, 0, (int) length - 2);
                inflater.reset();
                inflater.setInput(packed, 0, (int) length - 2);
                try {
                    int done = 0;
                    while (done < blockSize) {
                        int n = inflater.inflate(sector, done, blockSize - done);
                        if (n == 0 && (inflater.finished() || inflater.needsInput())) break;
                        done += n;
                    }
                    if (done != blockSize) throw new IOException("a PFSC block inflated to " + done + " bytes");
                } catch (DataFormatException e) {
                    throw new IOException(e);
                }
            }
            cached = index;
        }

        @Override
        public void read(long offset, byte[] buffer, int position, int length) throws IOException {
            while (length > 0) {
                int index = (int) (offset / blockSize);
                int within = (int) (offset % blockSize);
                int count = Math.min(length, blockSize - within);
                load(index);
                System.arraycopy(sector, within, buffer, position, count);
                offset += count;
                position += count;
                length -= count;
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------------

    static byte[] sha256(byte[]... parts) throws GeneralSecurityException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (byte[] part : parts) digest.update(part);
        return digest.digest();
    }

    static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    /** RSA-2048 decryption with PKCS#1 v1.5 padding, as RSACryptoServiceProvider.Decrypt(data, false) */
    static byte[] rsaDecrypt(byte[] cipher, String modulusHex, String exponentHex) throws IOException {
        BigInteger n = new BigInteger(modulusHex, 16), d = new BigInteger(exponentHex, 16);
        byte[] m = new BigInteger(1, cipher).modPow(d, n).toByteArray();
        byte[] padded = new byte[256];
        System.arraycopy(m, Math.max(0, m.length - 256), padded, Math.max(0, 256 - m.length), Math.min(256, m.length));
        if (padded[0] != 0 || padded[1] != 2) throw new IOException(RETAIL_MESSAGE);
        int zero = 2;
        while (zero < 256 && padded[zero] != 0) ++zero;
        if (zero >= 256) throw new IOException(RETAIL_MESSAGE);
        return Arrays.copyOfRange(padded, zero + 1, 256);
    }

    static final class Result {
        String contentId;
        List<String> warnings = new ArrayList<>();
    }

    static Result extract(FileChannel channel, File destination, Progress progress) throws IOException {
        try {
            return run(channel, destination, progress);
        } catch (GeneralSecurityException e) {
            throw new IOException("decryption failed: " + e.getMessage(), e);
        }
    }

    private static Result run(FileChannel channel, File destination, Progress progress) throws IOException, GeneralSecurityException {
        Result result = new Result();
        long length = channel.size();
        ChannelSource pkg = new ChannelSource(channel, 0, length);
        byte[] header = new byte[0x1000];
        pkg.read(0, header, 0, header.length);
        ByteBuffer h = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
        if (h.getInt(0) != 0x7F434E54) throw new IOException("This is not a PS4 PKG.");
        int entryCount = h.getInt(0x10), table = h.getInt(0x18);
        int idLength = 0;
        while (idLength < 0x30 && header[0x40 + idLength] != 0) ++idLength;
        result.contentId = new String(header, 0x40, idLength, StandardCharsets.US_ASCII);
        long pfsFlags = h.getLong(0x408), pfsOffset = h.getLong(0x410), pfsSize = h.getLong(0x418);
        if (pfsOffset < 0 || pfsSize < 0 || pfsOffset > length || pfsSize > length - pfsOffset) throw new IOException("Truncated PFS image.");
        progress.update("Opening " + result.contentId, 0, 1);

        byte[] keysMeta = null, imageMeta = null;
        int keysOffset = 0, imageOffset = 0, imageSize = 0;
        byte[] entries = new byte[32 * entryCount];
        pkg.read(table, entries, 0, entries.length);
        ByteBuffer e = ByteBuffer.wrap(entries).order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < entryCount; ++i) {
            int id = e.getInt(32 * i), offset = e.getInt(32 * i + 16), size = e.getInt(32 * i + 20);
            byte[] meta = Arrays.copyOfRange(entries, 32 * i, 32 * i + 32);
            Arrays.fill(meta, 24, 32, (byte) 0); // MetaEntry.GetBytes writes eight zero bytes after the six fields
            if (id == 0x10) {
                keysMeta = meta;
                keysOffset = offset;
            } else if (id == 0x20) {
                imageMeta = meta;
                imageOffset = offset;
                imageSize = size;
            }
        }
        if (keysMeta == null || imageMeta == null || imageSize <= 0 || imageSize % 16 != 0) throw new IOException(RETAIL_MESSAGE);
        byte[] key3 = new byte[256];
        pkg.read(keysOffset + 32 + 7 * 32 + 3 * 256, key3, 0, 256);
        byte[] dk3 = rsaDecrypt(key3, FakePkgKeys.DERIVED_KEY3_MODULUS, FakePkgKeys.DERIVED_KEY3_PRIVATE_EXPONENT);
        byte[] ivKey = sha256(imageMeta, dk3);
        byte[] image = new byte[imageSize];
        pkg.read(imageOffset, image, 0, imageSize);
        Cipher cbc = Cipher.getInstance("AES/CBC/NoPadding");
        cbc.init(Cipher.DECRYPT_MODE, new SecretKeySpec(Arrays.copyOfRange(ivKey, 16, 32), "AES"), new IvParameterSpec(Arrays.copyOfRange(ivKey, 0, 16)));
        byte[] ekpfs = rsaDecrypt(cbc.doFinal(image), FakePkgKeys.FAKE_MODULUS, FakePkgKeys.FAKE_PRIVATE_EXPONENT);

        Pfs outer = new Pfs(new ChannelSource(channel, pfsOffset, pfsSize), ekpfs, pfsFlags);
        Map<String, long[]> root = outer.list(0);
        long[] uroot = root.get("uroot");
        if (uroot == null) throw new IOException("invalid PFS image (no uroot)");
        long[] image0 = outer.list((int) uroot[1]).get("pfs_image.dat");
        if (image0 == null) throw new IOException("invalid PFS image (no pfs_image.dat)");
        Pfs inner = new Pfs(new PfscSource(outer.file((int) image0[1])), null, 0);
        Map<String, long[]> innerRoot = inner.list(0);
        long[] innerU = innerRoot.get("uroot");
        if (innerU == null) throw new IOException("invalid inner PFS image (no uroot)");
        Map<String, Integer> files = new HashMap<>();
        inner.walk((int) innerU[1], "", files);

        Map<String, PfsFile> chosen = new HashMap<>();
        for (String name : REQUIRED) {
            Integer exact = files.get("/" + name);
            if (exact != null) {
                chosen.put(name, inner.file(exact));
                continue;
            }
            String suffix = name.endsWith(".psarc") ? ".psarc" : name.endsWith(".qar") ? ".qar" : "pathid_list";
            PfsFile best = null;
            String bestPath = null;
            for (Map.Entry<String, Integer> f : files.entrySet()) {
                String path = f.getKey();
                if (path.startsWith("/sce_sys/") || path.startsWith("/sce_module/")) continue;
                if (!(suffix.equals("pathid_list") ? path.contains(suffix) : path.toLowerCase().endsWith(suffix))) continue;
                PfsFile candidate = inner.file(f.getValue());
                if (best == null || candidate.size > best.size) {
                    best = candidate;
                    bestPath = path;
                }
            }
            if (best != null) {
                chosen.put(name, best);
                result.warnings.add(name + " not at the package root; using " + bestPath);
            }
        }
        PfsFile psarc = chosen.get("chunk1.psarc"), qar = chosen.get("texture.qar"), pathid = chosen.get("pathid_list_ps4.bin");
        if (psarc == null || qar == null) throw new IOException(NOT_PT_MESSAGE);
        byte[] magic = new byte[4];
        psarc.read(0, magic, 0, 4);
        byte[] footer = new byte[0x24];
        qar.read(qar.size - 0x24, footer, 0, 0x24);
        if (!new String(magic, StandardCharsets.US_ASCII).equals("PSAR") || footer[0x16] != 'a' || footer[0x17] != 'q') throw new IOException(NOT_PT_MESSAGE);
        if (pathid != null && pathid.size < 64L * 1024 * 1024) {
            byte[] list = new byte[(int) pathid.size];
            pathid.read(0, list, 0, list.length);
            String title = result.contentId.length() >= 16 ? result.contentId.substring(7, 16) : "";
            boolean known = title.equals("CUSA01127") || title.equals("CUSA01114") || title.equals("CUSA01098");
            if (!new String(list, StandardCharsets.ISO_8859_1).contains("pt14_") && !known) throw new IOException(NOT_PT_MESSAGE);
        } else {
            result.warnings.add("pathid_list_ps4.bin is missing: enhanced textures cannot be generated; the game runs without it");
        }

        if (!destination.isDirectory() && !destination.mkdirs()) throw new IOException("cannot create " + destination);
        long total = 0, done = 0;
        for (String name : REQUIRED) if (chosen.get(name) != null) total += chosen.get(name).size;
        byte[] buffer = new byte[1 << 20];
        for (String name : REQUIRED) {
            PfsFile file = chosen.get(name);
            if (file == null) continue;
            File part = new File(destination, name + ".part");
            try (FileOutputStream out = new FileOutputStream(part)) {
                for (long pos = 0; pos < file.size; ) {
                    int count = (int) Math.min(buffer.length, file.size - pos);
                    file.read(pos, buffer, 0, count);
                    out.write(buffer, 0, count);
                    pos += count;
                    done += count;
                    progress.update("Extracting " + name, done, total);
                }
                out.getFD().sync();
            }
            File target = new File(destination, name);
            if (target.exists() && !target.delete()) throw new IOException("cannot replace " + target);
            if (!part.renameTo(target)) throw new IOException("cannot rename " + part);
        }
        return result;
    }
}
