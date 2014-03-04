package com.mikeprimm.WorldMapper;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.BitSet;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import org.spout.nbt.Tag;
import org.spout.nbt.stream.NBTInputStream;
import org.spout.nbt.stream.NBTOutputStream;

public class RegionFile {
    private File rfile;
    private BitSet alloc_table = new BitSet();
    private int[] chunkoff = new int[1024];
    private int[] chunklen = new int[1024];
    private int[] timestamp = new int[1024];
    private RandomAccessFile raf;
    
    public RegionFile(File f) throws IOException {
        rfile = f;
        if (rfile.exists()) {
            load();
        }
    }
    public void cleanup() {
        alloc_table.clear();    // Reset tables
        alloc_table.set(0); // First two are always allocated
        alloc_table.set(1);
        chunkoff = new int[1024];
        chunklen = new int[1024];
        timestamp = new int[1024];
        if (raf != null) { try { raf.close(); } catch (IOException x) {};  raf = null; }
    }
    
    public void load() throws IOException {
        cleanup();
        
        // Now create access file to read chunk
        raf = new RandomAccessFile(rfile, "rw");
        long initlen = raf.length();
        if (initlen < 8192) {   // Proper file needs to be at least 8192 bytes
            throw new IOException("Missing initial chunk tables: length=" + initlen);
        }
        byte[] buf = new byte[4096];
        // First 4K is chunk offset/length data
        raf.read(buf);  // read bytes
        for (int i = 0, boff = 0; i < 1024; i++) {
            for (int b = 0; b < 3; b++) {
                chunkoff[i] = (chunkoff[i] << 8) | (255 & buf[boff++]);
            }
            chunklen[i] = (255 & buf[boff++]);
            // If zero, no sectors
            if (chunkoff[i] == 0) continue;
            // Now, mark sectors as allocated
            for (int sect = chunkoff[i]; sect < (chunkoff[i] + chunklen[i]); sect++) {
                if (alloc_table.get(sect)) {    // Already allocated?
                    throw new IOException("Bad chunk map: chunk " + i + " extends to already allocated sector " + sect + " in " + rfile.getPath());
                }
                alloc_table.set(sect);
            }
        }
        // Next 4K is timestamps
        raf.read(buf);  // read bytes
        for (int i = 0, boff = 0; i < 1024; i++) {
            for (int b = 0; b < 4; b++) {
                timestamp[i] = (timestamp[i] << 8) | (255 & buf[boff++]);
            }
        }
    }
    
    // Map X,Z chunk coord to index
    private final int getIndex(int x, int z) {
        return x + (z * 32);
    }
    
    // Write chunk timestamp
    public void writeChunkTimestamp(int x, int z, int timestamp) throws IOException {
        int idx = getIndex(x, z);
        this.timestamp[idx] = timestamp;
        raf.seek(4096L + (idx*4));
        raf.writeInt(timestamp);
    }
    
    // Write chunk offset and count
    private void writeChunkOffsetCnt(int x, int z, int off, int cnt) throws IOException {
        int idx = getIndex(x, z);
        this.chunkoff[idx] = off;
        this.chunklen[idx] = cnt;
        raf.seek(idx*4L);
        raf.writeInt((off << 8) | cnt);
    }
    
    // Check if chunk exists
    public boolean chunkExists(int x, int z) {
        if ((x < 0) || (x > 31) || (z < 0) || (z > 31)) {
            return false;
        }
        int idx = getIndex(x, z);
        return (chunkoff[idx] > 0) && (chunklen[idx] > 0);
    }
    
    // Read chunk, return as data stream
    public Tag<?> readChunk(int x, int z) throws IOException {
        // Sanity check chunk coordinates
        if ((x < 0) || (x > 31) || (z < 0) || (z > 31)) {
            return null;
        }
        int idx = getIndex(x, z);   // Get index for chunk
        if (chunkoff[idx] <= 0) {   // Unallocated chunk?
            return null;
        }
        long baseoff = 4096L * chunkoff[idx];   // Get offset
        int cnt = chunklen[idx]; // Get chunk count
        raf.seek(baseoff);  // Seek to chunk
        int clen = raf.readInt();   // Read chunk byte count
        if ((clen > (cnt * 4096)) || (clen <= 0)) {  // Not enough data?
            throw new IOException("Length longer than space: " + clen + " > " + (cnt * 4096));
        }
        int encoding = raf.readByte(); // Get encoding for chunk
        byte[] buf = null;
        InputStream in = null;
        switch (encoding) {
            case 1:
                buf = new byte[clen - 1];   // Get buffer for bytes (length has 1 extra)
                raf.read(buf);  // Read whole compressed chunk
                // And return stream to decompress it
                in = new GZIPInputStream(new ByteArrayInputStream(buf));
                break;
            case 2:
                buf = new byte[clen - 1];   // Get buffer for bytes (length has 1 extra)
                raf.read(buf);  // Read whole compressed chunk
                in = new InflaterInputStream(new ByteArrayInputStream(buf));
                break;
            default:
                throw new IOException("Bad encoding=" + encoding);
        }
        NBTInputStream nis = new NBTInputStream(in, false);
        try {
            return nis.readTag();
        } finally {
            nis.close();
        }
    }
    // Write chunk NBT to file
    public boolean writeChunk(int x, int z, Tag<?> lvl) throws IOException {
        // Sanity check chunk coordinates
        if ((x < 0) || (x > 31) || (z < 0) || (z > 31)) {
            return false;
        }
        
        BufferOutputStream baos = new BufferOutputStream();
        DeflaterOutputStream dos = new DeflaterOutputStream(baos);
        NBTOutputStream nbtos = new NBTOutputStream(dos, false);
        try {
            nbtos.writeTag(lvl);
        } finally {
            nbtos.close();
        }
        byte[] cbytes = baos.buf;
        int clen = baos.len;
        
        int idx = getIndex(x, z);   // Get index
        int curoff = this.chunkoff[idx];
        int curlen = this.chunklen[idx];
        int newlen = ((clen + 5) / 4096) + 1;

        // If allocated 
        if (curoff > 0) {
            // If right size, we're good to reuse
            if (newlen == curlen) {
            }
            // If need more space, free it
            else if (newlen > curlen) {
                for (int off = curoff; off < (curoff + curlen); off++) {
                    this.alloc_table.clear(off);
                }
                curoff = 0;
                curlen = 0;
            }
            // Else, need less - free extra
            else {
                for (int off = curoff + newlen; off < (curoff + curlen); off++) {
                    this.alloc_table.clear(off);
                }
                curlen = newlen;
            }
        }
        // If not allocated, allocate new space
        if (curoff == 0) {
            int cnt;
            int off;
            // Find big enough space
            for (off = 2, cnt = 0; cnt < newlen; off++) {
                if (alloc_table.get(off)) {  // Allocated?
                    cnt = 0;
                    curoff = 0;
                }
                else {  // Free space
                    if (curoff == 0) {
                        curoff = off;
                        cnt = 1;
                    }
                    else {
                        cnt++;
                    }
                }
            }
            // Set bits allocated
            for (off = curoff; off < (curoff + newlen); off++) {
                alloc_table.set(off);
            }
            curlen = newlen;
        }
        // Check if long enough
        if (raf.length() < (4096L * (curoff + curlen))) {
            raf.setLength(4096L * (curoff + curlen));
        }
        raf.seek(4096L * curoff);
        raf.writeInt(clen + 1);
        raf.writeByte(2);
        raf.write(cbytes, 0, clen);
        writeChunkOffsetCnt(x, z, curoff, curlen);
        
        return true;
    }
    // Delete chunk
    public boolean deleteChunk(int x, int z) throws IOException {
        // Sanity check chunk coordinates
        if ((x < 0) || (x > 31) || (z < 0) || (z > 31)) {
            return false;
        }
        int idx = getIndex(x, z);   // Get index
        int curoff = this.chunkoff[idx];
        int curlen = this.chunklen[idx];

        // If allocated 
        if (curoff > 0) {
            // Free space
            for (int off = curoff; off < (curoff + curlen); off++) {
                this.alloc_table.clear(off);
            }
        }
        writeChunkOffsetCnt(x, z, 0, 0);
        
        return true;
    }
}
