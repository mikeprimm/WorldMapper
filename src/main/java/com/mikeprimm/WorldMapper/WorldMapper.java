package com.mikeprimm.WorldMapper;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.BitSet;

import org.spout.nbt.CompoundMap;
import org.spout.nbt.CompoundTag;
import org.spout.nbt.Tag;
import org.spout.nbt.stream.NBTInputStream;

import com.google.gson.Gson;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;

public class WorldMapper {
    // Index is block ID * 16 + meta, value is new block ID *16 + meta
    private static int blkid_map[] = new int[4096 * 16];
    private static BitSet blkid_toss_tileentity = new BitSet(); // Flags which source IDs to scrap tile entity

    private static class BlockMapping {
        private int blkid;
        private int meta = -1;
        private int newblkid;
        private int newmeta = -1;
        private boolean tosstileentity = false;
    }
    private static class MappingConfig {
        private BlockMapping[] blocks;
    }
    private static void defaultMap() {
        // Default to trivial mapping
        for (int i = 0; i < blkid_map.length; i++) {
            blkid_map[i] = i;
        }
        blkid_toss_tileentity.clear();
    }
    private static void processMapDefinition(MappingConfig cfg) throws IOException {
        if (cfg.blocks == null) {
            throw new IOException("'blocks' array not found.");
        }
        // Default the mapping
        defaultMap();
        // Traverse block mapping objects
        for (BlockMapping mb : cfg.blocks) {
            if (mb == null) continue;
            // Now, fill in the mapping records
            if (mb.meta < 0) {
                for (int meta = 0; meta < 16; meta++) {
                    int idx = (mb.blkid*16) + meta;
                    if (mb.newmeta < 0)
                        blkid_map[idx] = (mb.newblkid * 16) + meta;
                    else
                        blkid_map[idx] = (mb.newblkid * 16) + mb.newmeta;
                    // If scrapping tile entity
                    if(mb.tosstileentity) {
                        blkid_toss_tileentity.set(idx);
                    }
                }
            }
            else {
                if (mb.newmeta < 0)
                    blkid_map[(mb.blkid*16) + mb.meta] = (mb.newblkid * 16) + mb.meta;
                else
                    blkid_map[(mb.blkid*16) + mb.meta] = (mb.newblkid * 16) + mb.newmeta;
                if(mb.tosstileentity) {
                    blkid_toss_tileentity.set((mb.blkid*16) + mb.meta);
                }
            }
        }
        // Print parsed mapping
        for (int i = 0; i < blkid_map.length; i++) {
            if (blkid_map[i] != i) {
                System.out.println("Map " + (i>>4) + ":" + (i & 0xF) + " to " + (blkid_map[i] >> 4) + ":" + (blkid_map[i] & 0xF) + 
                        (blkid_toss_tileentity.get(i)?", discard tile entity":""));
            }
        }
    }
    /**
     * Main routine for running mapper
     * 
     * @param args - <world directory> <map-file> <destination directory>
     */
    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("Required arguments: src-world-dir map-file.json dest-world-dir");
            System.exit(1);
        }
        // Get and validate source directory
        File srcdir = new File(args[0]);
        if (!srcdir.isDirectory()) {
            System.err.println("Source '" + args[0] + "' must be existing world directory.");
            System.exit(1);
        }
        // Get and read map file
        File mapfile = new File(args[1]);
        if (!mapfile.isFile()) {
            System.err.println("Mapping file '" + args[0] + "' must be existing JSON encoded mapping file.");
            System.exit(1);
        }
        // Read and parse the file
        Gson parser = new Gson();
        Reader rdr = null;
        try {
            rdr = new FileReader(mapfile);
            MappingConfig cfg = parser.fromJson(rdr,  MappingConfig.class);
            processMapDefinition(cfg);
        } catch (JsonSyntaxException jsx) {
            System.err.println("Mapping file syntax error: " + jsx.getMessage());
            System.exit(1);
        } catch (JsonIOException jiox) {
            System.err.println("Mapping file I/O error: " + jiox.getMessage());
            System.exit(1);
        } catch (IOException iox) {
            System.err.println("Mapping file error: " + iox.getMessage());
            System.exit(1);
        } finally {
            if (rdr != null) { try { rdr.close(); } catch (IOException iox) {} }
        }
        // Check destination
        File destdir = new File(args[2]);
        if (destdir.exists() == false) {    // Create if needed
            destdir.mkdirs();
        }
        if (!destdir.isDirectory()) {
            System.err.println("Destination '" + args[1] + "' is not directory.");
            System.exit(1);
        }
        try {
            if (srcdir.getCanonicalPath().equals(destdir.getCanonicalPath())) {
                System.err.println("Destination directory cannot be same as source directory.");
                System.exit(1);
            }
        } catch (IOException e) {
            System.err.println("Destination directory cannot be same as source directory.");
            System.exit(1);
        }
        try {
            processWorldMapping(srcdir, destdir);
            
            System.out.println("World mapping completed");
            System.exit(0);
        } catch (IOException iox) {
            System.err.println(iox.getMessage());
            System.exit(1);
        }
    }
    
    private static void processWorldMapping(File src, File dest) throws IOException {
        File[] srcfiles = src.listFiles();
        if (srcfiles == null) return;
        
        for (File srcfile : srcfiles) {
            String srcname = srcfile.getName();
            if (srcfile.isDirectory()) {    // If directory, create copy in destination and recurse
                File destdir = new File(dest, srcname);
                destdir.mkdir();
                processWorldMapping(srcfile, destdir);
            }
            else if (srcname.endsWith(".mca")) {    // If region file
                processRegionFile(srcfile, new File(dest, srcname));
            }
            //TODO: other file types we need to handle : level.dat
            
            else {  // Else, just copy file
                processFileCopy(srcfile, new File(dest, srcname));
            }
        }
    }
    // Process a region file
    private static void processRegionFile(File srcfile, File destfile) throws IOException {
        // Load source region file
        RegionFile srcrf = new RegionFile(srcfile);
        srcrf.load();
        int cnt = 0;
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                if(srcrf.chunkExists(x, z)) {   // If chunk exists
                    cnt++;
                    System.out.println("Chunk " + x + "," + z + " exists");
                    DataInputStream dis = srcrf.readChunk(x, z);
                    if (dis != null) {
                        NBTInputStream nis = new NBTInputStream(dis, false);
                        Tag tag = nis.readTag();
                        if (tag instanceof CompoundTag) {
                            CompoundTag ct = (CompoundTag) tag;
                            CompoundMap cm = ct.getValue();
                            Tag lvl = cm.get("Level");
                            if (lvl instanceof CompoundTag) {
                                cm = ((CompoundTag)lvl).getValue();
                                for (String k : cm.keySet()) {
                                    System.out.println(k + ": " + cm.get(k).getType());
                                }
                            }
                        }
                    }
                }
            }
        }
        System.out.println("Region " + srcfile.getPath() + ", " + cnt + " chunks");
    }
    // Process a generic file (just copy)
    private static void processFileCopy(File srcfile, File destfile) throws IOException {
        byte[] buf = new byte[2048];
        FileInputStream fis = null;
        FileOutputStream fos = null;
        try {
            fis = new FileInputStream(srcfile);
            fos = new FileOutputStream(destfile);
            int rlen;
            while ((rlen = fis.read(buf)) > 0) {
                fos.write(buf,  0,  rlen);
            }
        } finally {
            if (fis != null) { try { fis.close(); } catch (IOException iox) {} }
            if (fos != null) { try { fos.close(); } catch (IOException iox) {} }
        }
        System.out.println("Copied " + srcfile.getPath() + " to " + destfile.getPath());
    }
}
