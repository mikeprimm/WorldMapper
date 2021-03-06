package com.mikeprimm.WorldMapper;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

import org.spout.nbt.ByteArrayTag;
import org.spout.nbt.CompoundMap;
import org.spout.nbt.CompoundTag;
import org.spout.nbt.ListTag;
import org.spout.nbt.Tag;
import org.spout.nbt.util.NBTMapper;

import com.google.gson.Gson;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;

public class WorldMapper {
    // Biome names, ordered by index/ID - lowercase with spaces removed (as done in MCPatcher)
    public static final String[] biomes = {
         "ocean", "plains", "desert", "extremehills", "forest", "taiga", "swampland", "river", "hell",
         "sky", "frozenocean", "frozenriver", "iceplains", "icemountains", "mushroomisland", "mushroomislandshore",
         "beach", "deserthills", "foresthills", "taigahills", "extremehillsedge", "jungle", "junglehills"
    };
         
    // Index is block ID * 16 + meta, value is new block ID *16 + meta
    private static int blkid_map[] = new int[4096 * 16];
    private static int biome_blkid_map[][] = new int[256][];
    private static BitSet blkid_biome_specific = new BitSet(); // Flags which source IDs to scrap tile entity
    private static BitSet blkid_toss_tileentity = new BitSet(); // Flags which source IDs to scrap tile entity
    private static BitSet blkid_toss_ifunsupported = new BitSet(); // Flags which source IDs to scrap if over air
    // For target above 65536
    private static ArrayList<int[]> blkid_random_map = new ArrayList<int[]>();

    private static class BlockMapping {
        private int blkid;
        private int meta = -1;
        private int newblkid;
        private int newmeta = -1;
        private int[] newRandomIDMeta = null;
        private boolean tosstileentity = false;
        private boolean tossifunsupported = false;
        private String biomes[] = null;
    }
    private static class MappingConfig {
        private BlockMapping[] blocks;
    }
    
    private static class MappedChunk {
        Tag<?> level;
        int bcnt;   // Number of blocks mapped
        int tescrubbed; // Number of tile entities scrubbed
        CompoundMap value;  // Base value for chunk
        List<CompoundTag> tileents; // List of tile entites (original)
        LinkedList<CompoundTag> new_tileents; // New list, if modified
        byte[] biomes; // biome data (ZX order)
        List<CompoundTag> sections; // Chunk sections
        boolean empty;

        @SuppressWarnings("unchecked")
        MappedChunk(Tag<?> lvl) throws IOException {
            level = lvl;
            CompoundMap val = NBTMapper.getTagValue(level, CompoundMap.class);
            value = NBTMapper.getTagValue(val.get("Level"), CompoundMap.class);
            if (value == null) throw new IOException("Chunk is missing Level data");

            this.tileents = NBTMapper.getTagValue(value.get("TileEntities"), List.class);
            if (tileents == null) throw new IOException("Chunk is missing TileEntities data");
            // Get biomes (ZX order)
            biomes = NBTMapper.getTagValue(value.get("Biomes"), byte[].class);
            if ((biomes == null) || (biomes.length < 256)) { throw new IOException("No value for Biomes in chunk"); }
            
            // Get sections of chunk
            sections = NBTMapper.getTagValue(value.get("Sections"), List.class);
            if (sections == null) { throw new IOException("No value for Sections in chunk"); }
            empty = sections.size() == 0;
            bcnt = tescrubbed = 0;
        }
        // Process chunk
        void processChunk() throws IOException {
            empty = true;
            // Loop through the sections
            for (CompoundTag sect : sections) {
                empty = processSection(sect.getValue()) & empty;
            }
            // If modified tile entities list, replace it
            if (new_tileents != null) {
                value.put("TileEntities", new ListTag<CompoundTag>("TileEntities", CompoundTag.class, new_tileents));
                new_tileents = null;
            }
        }
        CompoundMap findSection(int y) {
            for (CompoundTag sect : sections) {
                CompoundMap map = sect.getValue();
                Byte yy = NBTMapper.getTagValue(map.get("Y"), Byte.class);
                if (yy.intValue() == y) {
                    return map;
                }
            }
            return null;
        }
        boolean isAirBelow(byte[] blocks, byte[] extblocks, int off, Byte y) {
            int yy = ((off >> 8) & 0xF);
            int id = 0;
            if (yy > 0) {   // Same section?
                off -= 256;
                int extid = 0;
                id = (255 & blocks[off]);
                if (extblocks != null) {
                    if ((off & 1) == 0) { // Even values
                        id |= ((extid & 0xF) << 8);
                    }
                    else {
                        id |= ((extid & 0xF0) << 4);
                    }
                }
            }
            else {
                y = (byte)(y.intValue() - 1);
                CompoundMap sect = findSection(y);
                if (sect != null) {
                    return isAirBelow(NBTMapper.getTagValue(sect.get("Blocks"), byte[].class), 
                            NBTMapper.getTagValue(sect.get("Add"), byte[].class), off + 4096, y);
                }
            }
            return (id == 0);
        }
        boolean processSection(CompoundMap sect) throws IOException {
            Byte y = NBTMapper.getTagValue(sect.get("Y"), Byte.class);
            if (y == null) throw new IOException("Section missing Y field");
            byte[] blocks = NBTMapper.getTagValue(sect.get("Blocks"), byte[].class);
            int yoff = y.intValue() * 16; // Base Y value of section
            if ((blocks == null) || (blocks.length < 4096)) throw new IOException("Section missing Blocks field");
            byte[] extblocks = NBTMapper.getTagValue(sect.get("Add"), byte[].class); // Might be null
            if ((extblocks != null) && (extblocks.length < 2048))  throw new IOException("Section missing Data field");
            byte[] data = NBTMapper.getTagValue(sect.get("Data"), byte[].class);
            if ((data == null) || (data.length < 2048)) throw new IOException("Section missing Data field");
            
            boolean isEmpty = true;
            for (int i = 0, j = 0; i < 4096; j++) { // YZX order
                int id, meta;
                int extid = 0;
                int datavals = data[j];
                int idmataval = 0;
                int newidmetaval;
                if (extblocks != null) {
                    extid = 255 & extblocks[j];
                }
                // Process even values
                id = (255 & blocks[i]) | ((extid & 0xF) << 8);
                if (id != 0) {
                    meta = (datavals & 0xF);
                    idmataval = (id << 4) | meta;
                    newidmetaval = blkid_map[idmataval];
                    int biomeid = 0xFF & biomes[i & 0xFF];
                    newidmetaval = getBiomeSpecificID(idmataval, biomeid);
                    // Unsupported reed?
                    if (blkid_toss_ifunsupported.get(idmataval) && isAirBelow(blocks, extblocks, i, y)) {
                        newidmetaval = 0;
                        System.out.println(String.format("Unsupported block: %d,%d,%d", (i & 0xF), ((i >> 8) & 0xF) + yoff, (i >> 4) & 0xF));
                    }
                    
                    if (newidmetaval != idmataval) {    // New value?
                        if (blkid_toss_tileentity.get(idmataval)) { // If scrubbing tile entity
                            deleteTileEntity(i & 0xF, ((i >> 8) & 0xF) + yoff, (i >> 4) & 0xF, idmataval);
                        }
                        id = (newidmetaval >> 4);
                        meta = (newidmetaval & 0xF);
                        if ((id > 256) && (extblocks == null)) {
                            extblocks = new byte[2048];
                            sect.put("Add", new ByteArrayTag("Add", extblocks));
                        }
                        blocks[i] = (byte)(255 & id);
                        if (extblocks != null) {
                            extid = (byte) ((extid & 0xF0) | ((id >> 8) & 0xF));
                        }
                        datavals = (byte) ((datavals & 0xF0) | (meta & 0xF));
                        bcnt++;
                    }
                    if (newidmetaval != 0) {
                        isEmpty = false;
                    }
                }
                i++;
                // Process odd values
                id = (255 & blocks[i]) | ((extid & 0xF0) << 4);
                if (id != 0) {
                    meta = (datavals & 0xF0) >> 4;
                    idmataval = (id << 4) | meta;
                    newidmetaval = blkid_map[idmataval];
                    int biomeid = 0xFF & biomes[i & 0xFF];
                    newidmetaval = getBiomeSpecificID(idmataval, biomeid);
                    // Unsupported reed?
                    if (blkid_toss_ifunsupported.get(idmataval) && isAirBelow(blocks, extblocks, i, y)) {
                        newidmetaval = 0;
                        System.out.println(String.format("Unsupported block: %d,%d,%d", (i & 0xF), ((i >> 8) & 0xF) + yoff, (i >> 4) & 0xF));
                    }
                    if (newidmetaval != idmataval) {    // New value?
                        if (blkid_toss_tileentity.get(idmataval)) { // If scrubbing tile entity
                            deleteTileEntity(i & 0xF, ((i >> 8) & 0xF) + yoff, (i >> 4) & 0xF, idmataval);
                        }
                        id = (newidmetaval >> 4);
                        meta = (newidmetaval & 0xF);
                        if ((id > 256) && (extblocks == null)) {
                            extblocks = new byte[2048];
                            sect.put("Add", new ByteArrayTag("Add", extblocks));
                        }
                        blocks[i] = (byte)(255 & id);
                        if (extblocks != null) {
                            extid = (byte) ((extid & 0x0F) | ((id >> 4) & 0xF0));
                        }
                        datavals = (byte) ((datavals & 0x0F) | ((meta << 4) & 0xF0));
                        bcnt++;
                    }
                    if (newidmetaval != 0) {
                        isEmpty = false;
                    }
                }
                i++;
                data[j] = (byte)(0xFF & datavals);
                if (extblocks != null) {
                    extblocks[j] = (byte)(0xFF & extid);
                }
            }
            return isEmpty;
        }
        private void deleteTileEntity(int x, int y, int z, int idmeta) {
            if (new_tileents == null) {
                new_tileents = new LinkedList<CompoundTag>(tileents);
            }
            Iterator<CompoundTag> te_iter = new_tileents.iterator();
            while (te_iter.hasNext()) {
                CompoundTag te = te_iter.next();
                CompoundMap ted = te.getValue();
                if (ted == null) continue;
                Integer tex = NBTMapper.getTagValue(ted.get("x"), Integer.class);
                Integer tey = NBTMapper.getTagValue(ted.get("y"), Integer.class);
                Integer tez = NBTMapper.getTagValue(ted.get("z"), Integer.class);
                if ((tex == null) || (tey == null) || (tez == null)) continue;
                // If matches on chunk relatve coordinates
                if (((tex & 0xF) == x) && (tey == y) && ((tez & 0xF) == z)) {
                    te_iter.remove();
                    tescrubbed++;
                    return;
                }
            }
        }
    }
    private static void defaultMap() {
        // Default to trivial mapping
        for (int i = 0; i < blkid_map.length; i++) {
            blkid_map[i] = i;
        }
        for (int i = 0; i < biome_blkid_map.length; i++) {
            biome_blkid_map[i] = null;
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

            if (mb.biomes != null) {    // Biome specific?
                // Mark blockID+meta as having biome specific mapping
                if (mb.meta < 0) {
                    for (int meta = 0; meta < 16; meta++) {
                        blkid_biome_specific.set((mb.blkid*16) + meta);
                    }
                }
                else {
                    blkid_biome_specific.set((mb.blkid*16) + mb.meta);
                }
                for (int bidx = 0; bidx < mb.biomes.length; bidx++) {
                    int biomeid = findBiomeIndex(mb.biomes[bidx]);
                    if (biomeid < 0) {
                        throw new IOException("Invalid biome name: " + mb.biomes[bidx]);
                    }
                    if (biome_blkid_map[biomeid] == null) {
                        biome_blkid_map[biomeid] = new int[blkid_map.length];
                        for (int i = 0; i < blkid_map.length; i++) {
                            biome_blkid_map[biomeid][i] = i;
                        }
                    }
                    updateMapping(mb, biome_blkid_map[biomeid]);
                }
            }
            else {
                updateMapping(mb, blkid_map);
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
    
    private static Random rnd = new Random();
    
    private static final int RANDOM_INDEX = 65536;
    
    private static void updateMapping(BlockMapping mb, int[] map) {
        // Now, fill in the mapping records
        if (mb.newRandomIDMeta != null) { // If random dest
            int destidx = RANDOM_INDEX + blkid_random_map.size();
            int[] v = new int[mb.newRandomIDMeta.length / 2];
            for (int i = 0; i < v.length; i++) {
                v[i] = mb.newRandomIDMeta[2*i] * 16 + mb.newRandomIDMeta[2*i+1];
            }
            blkid_random_map.add(v);
            if (mb.meta < 0) {
                for (int meta = 0; meta < 16; meta++) {
                    int idx = (mb.blkid*16) + meta;
                    map[idx] = destidx;
                    // If scrapping tile entity
                    if(mb.tosstileentity) {
                        blkid_toss_tileentity.set(idx);
                    }
                    if(mb.tossifunsupported) {
                        blkid_toss_ifunsupported.set(idx);
                    }
                }   
            }
            else {
                map[(mb.blkid*16) + mb.meta] = destidx;
                if(mb.tosstileentity) {
                    blkid_toss_tileentity.set((mb.blkid*16) + mb.meta);
                }
                if(mb.tossifunsupported) {
                    blkid_toss_ifunsupported.set((mb.blkid*16) + mb.meta);
                }
            }            
        }
        else if (mb.meta < 0) {
            for (int meta = 0; meta < 16; meta++) {
                int idx = (mb.blkid*16) + meta;
                if (mb.newmeta < 0) {
                    map[idx] = (mb.newblkid * 16) + meta;
                }
                else {
                    map[idx] = (mb.newblkid * 16) + mb.newmeta;
                }
                // If scrapping tile entity
                if(mb.tosstileentity) {
                    blkid_toss_tileentity.set(idx);
                }
                if(mb.tossifunsupported) {
                    blkid_toss_ifunsupported.set(idx);
                }
            }   
        }
        else {
            if (mb.newmeta < 0) {
                map[(mb.blkid*16) + mb.meta] = (mb.newblkid * 16) + mb.meta;
            }
            else {
                map[(mb.blkid*16) + mb.meta] = (mb.newblkid * 16) + mb.newmeta;
            }
            if(mb.tosstileentity) {
                blkid_toss_tileentity.set((mb.blkid*16) + mb.meta);
            }
            if(mb.tossifunsupported) {
                blkid_toss_ifunsupported.set((mb.blkid*16) + mb.meta);
            }
        }
    }
    private static boolean update = false;
    
    private static void doMerge(String[] args) {
        // Get and validate source directory
        File srcdir = new File(args[1]);
        if (!srcdir.isDirectory()) {
            System.err.println("Source '" + args[1] + "' must be existing world directory.");
            System.exit(1);
        }
        // Check destination
        File destdir = new File(args[2]);
        if (destdir.exists() == false) {    // Create if needed
            destdir.mkdirs();
        }
        if (!destdir.isDirectory()) {
            System.err.println("Destination '" + args[2] + "' is not directory.");
            System.exit(1);
        }
        try {
            processWorldMerge(srcdir, destdir);
            
            System.out.println("World mapping completed");
            System.exit(0);
        } catch (IOException iox) {
            System.err.println(iox.getMessage());
            System.exit(1);
        }
    }
    
    private static void processWorldMerge(File src, File dest) throws IOException {
        File[] srcfiles = src.listFiles();
        if (srcfiles == null) return;
        
        for (File srcfile : srcfiles) {
            String srcname = srcfile.getName();
            if (srcfile.isDirectory()) {    // If directory, create copy in destination and recurse
                File destdir = new File(dest, srcname);
                destdir.mkdir();
                processWorldMerge(srcfile, destdir);
            }
            else if (srcname.endsWith(".mca")) {    // If region file
                // Merge region files
                mergeRegionFile(srcfile, new File(dest, srcname));;
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
        if (args[0].equals("merge")) {  // Merge argv[1] into argv[2] world directory
            doMerge(args);
            return;
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
        if ((args.length > 3) && args[3].equals("update")) {
            update = true;
            System.out.println("Update changed files only");
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
            else if (srcname.endsWith(".schematic")) {  // If schematic file
                processSchematicFile(srcfile, new File(dest, srcname));
            }
            else if (srcname.endsWith(".bo2")) {  // If schematic file
                processBO2File(srcfile, new File(dest, srcname));
            }
            //TODO: other file types we need to handle : level.dat
            
            else {  // Else, just copy file
                processFileCopy(srcfile, new File(dest, srcname));
            }
        }
    }
    // Process a region file
    private static void processRegionFile(File srcfile, File destfile) throws IOException {
        boolean success = false;
        int bcnt = 0;
        int tecnt = 0;
        int cupdated = 0;
        RegionFile destf = null;
        if (update && (srcfile.lastModified() == destfile.lastModified())) {
            System.out.println("Region " + destfile.getPath() + ": source unchaged");
            return;
        }
        try {
            // Copy source file to destination
            processFileCopy(srcfile, destfile);
            // Load region file
            destf = new RegionFile(destfile);
            destf.load();
            int cnt = 0;
            int dcnt = 0;
            for (int x = 0; x < 32; x++) {
                for (int z = 0; z < 32; z++) {
                    if(destf.chunkExists(x, z)) {   // If chunk exists
                        cnt++;
                        Tag<?> tag = destf.readChunk(x, z);
                        if (tag == null) { System.err.println("Chunk " + x + "," + z + " exists but not read"); continue; }
                        MappedChunk mc = new MappedChunk(tag);
                        mc.processChunk();
                        // Test if chunk is empty
                        if (mc.empty) {
                            destf.deleteChunk(x, z);    // Delete it
                            dcnt++;
                        }
                        // Test if updated
                        else if (mc.bcnt > 0) {
                            bcnt += mc.bcnt;
                            tecnt += mc.tescrubbed;
                            cupdated++;
                            // Write updated chunk data
                            destf.writeChunk(x, z, mc.level);
                        }
                    }
                }
            }
            success = true;
            if (dcnt == cnt) {  // Deleted all the chunks found?
                System.out.println("Region " + destfile.getPath() + ", all " + cnt + " chunks deleted: file dropped");
                destfile.delete();
            }
            else {
                System.out.println("Region " + destfile.getPath() + ", " + cnt + " chunks: updated " + bcnt + " blocks in " + cupdated + " chunks, Deleted " + dcnt + " chunks, " + tecnt + " TileEntities scrubbed");
            }
            		
        } finally {
            if (!success) {
                destfile.delete();
            }
            else {
                destfile.setLastModified(srcfile.lastModified()); // Preserve last modified
            }
            if (destf != null) {
                destf.cleanup();
            }
        }
    }

    // Process a schematic file
    private static void processSchematicFile(File srcfile, File destfile) throws IOException {
        boolean success = false;
        int bcnt = 0;
        int tecnt = 0;
        RegionFile destf = null;
        if (update && (srcfile.lastModified() == destfile.lastModified())) {
            System.out.println("Schematic file " + destfile.getPath() + ": source unchaged");
            return;
        }
        try {
            WESchematicFile schfile = new WESchematicFile();
            schfile.load(srcfile);  // Load it
            for (int x = 0; x < schfile.width; x++) {
                for (int y = 0; y < schfile.height; y++) {
                    for (int z = 0; z < schfile.length; z++) {
                        int id = schfile.getID(x, y, z);
                        if (id != 0) {
                            int meta = schfile.getData(x, y, z);
                            int idmataval = (id << 4) | meta;
                            int newidmetaval = blkid_map[idmataval];
                            
                            newidmetaval = getBiomeSpecificID(idmataval, 0);

                            if (newidmetaval != idmataval) {    // New value?
                                if (blkid_toss_tileentity.get(idmataval)) { // If scrubbing tile entity
                                    schfile.deleteTileEntity(x, y, z);
                                    tecnt++;
                                }
                                id = (newidmetaval >> 4);
                                meta = (newidmetaval & 0xF);
                                schfile.setIDAndData(x, y, z, id, meta);
                                bcnt++;
                            }
                        }
                    }
                }
            }
            schfile.save(destfile);
            
            success = true;

            System.out.println("Schematic " + destfile.getPath() + ", updated " + bcnt + " blocks, stripped " + tecnt + " tile entities");
        } catch (IOException iox) {
            System.out.println("Schematic " + destfile.getPath() + " FAILED - " + iox.getMessage());
        } finally {
            if (!success) {
                destfile.delete();
            }
            else {
                destfile.setLastModified(srcfile.lastModified()); // Preserve last modified
            }
            if (destf != null) {
                destf.cleanup();
            }
        }
    }

    // Process a BO2 file
    private static void processBO2File(File srcfile, File destfile) throws IOException {
        boolean success = false;
        int bcnt = 0;
        RegionFile destf = null;
        if (update && (srcfile.lastModified() == destfile.lastModified())) {
            System.out.println("BO2 file " + destfile.getPath() + ": source unchaged");
            return;
        }
        try {
            WorldPainterBO2File bo2file = new WorldPainterBO2File();
            bo2file.load(srcfile);  // Load it
            for (int idx = 0; idx < bo2file.dataCount(); idx++) {
                int id = bo2file.getID(idx);
                if (id != 0) {
                    int meta = bo2file.getData(idx);
                    int idmataval = (id << 4) | meta;
                    int newidmetaval = blkid_map[idmataval];
                            
                    newidmetaval = getBiomeSpecificID(idmataval, 0);

                    if (newidmetaval != idmataval) {    // New value?
                        id = (newidmetaval >> 4);
                        meta = (newidmetaval & 0xF);
                        bo2file.setIDAndData(idx, id, meta);
                        bcnt++;
                    }
                }
            }
            bo2file.save(destfile);
            
            success = true;

            System.out.println("BO2 file " + destfile.getPath() + ", updated " + bcnt + " blocks");
        } catch (IOException iox) {
            System.out.println("BO2 file " + destfile.getPath() + " FAILED - " + iox.getMessage());
        } finally {
            if (!success) {
                destfile.delete();
            }
            else {
                destfile.setLastModified(srcfile.lastModified()); // Preserve last modified
            }
            if (destf != null) {
                destf.cleanup();
            }
        }
    }

    private static void close(Closeable closable) {
        if (closable != null) {
            try {
                closable.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    // Process a generic file (just copy)
    private static void processFileCopy(File source, File target) throws IOException {
        FileChannel in = null;
        FileChannel out = null;

        if (update && (source.lastModified() == target.lastModified())) {
            System.out.println("Skipped " + target.getPath() + ": source unchanged");
            return;
        }

        try {
            in = new FileInputStream(source).getChannel();
            out = new FileOutputStream(target).getChannel();

            long size = in.size();
            long transferred = in.transferTo(0, size, out);

            while(transferred != size){
                transferred += in.transferTo(transferred, size - transferred, out);
            }
        } finally {
            close(in);
            close(out);
        }
        target.setLastModified(source.lastModified()); // Preserve last modified
        
        System.out.println("Copied " + source.getPath() + " to " + target.getPath());
    }
    
    // Merge region files
    private static void mergeRegionFile(File srcfile, File destfile) throws IOException {
        RegionFile srcf = null;
        RegionFile destf = null;
        try {
            if (destfile.exists() == false) {   // No corresponding destination?
                // Copy source file to destination
                processFileCopy(srcfile, destfile);
            }
            else {  // Else update it
                // Load region file
                srcf = new RegionFile(srcfile);
                srcf.load();
                boolean allreplaced = true;
                for (int x = 0; allreplaced && (x < 32); x++) {
                    for (int z = 0; allreplaced && (z < 32); z++) {
                        if(!srcf.chunkExists(x, z)) {   // If chunk doesn't exists
                            allreplaced = false;
                        }
                    }
                }
                if (allreplaced) {
                    // Copy source file to destination
                    processFileCopy(srcfile, destfile);
                    return;
                }
                // Load region file
                destf = new RegionFile(destfile);
                destf.load();
                int cnt = 0;
                for (int x = 0; x < 32; x++) {
                    for (int z = 0; z < 32; z++) {
                        if(srcf.chunkExists(x, z)) {   // If chunk exists
                            cnt++;
                            Tag<?> tag = srcf.readChunk(x, z);
                            if (tag == null) { System.err.println("Chunk " + x + "," + z + " exists but not read"); continue; }
                            destf.writeChunk(x, z, tag);    // Write to file
                        }
                    }
                }
                System.out.println("Region " + srcfile.getPath() + ": copied " + cnt + " chunks to " + destfile.getPath());
            }
        } finally {
            if (destf != null) {
                destf.cleanup();
            }
            if (srcf != null) {
                srcf.cleanup();
            }
        }
    }

    private static int findBiomeIndex(String name) {
        String n = name.toLowerCase().replace(" ", "");
        for (int i = 0; i < biomes.length; i++) {
            if (biomes[i].equals(n)) {
                return i;
            }
        }
        return -1;
    }
    private static int getBiomeSpecificID(int idmetaval, int biomeid) {
        if (idmetaval == 0) return 0;
        
        int id = blkid_map[idmetaval];
        // If biome specific mapping defined?
        if (blkid_biome_specific.get(idmetaval)) {
            int[] map = biome_blkid_map[biomeid];
            if (map != null) {
                int newid = map[idmetaval];
                if (newid != idmetaval) {
                    id = newid;
                }
            }
        }
        if (id >= RANDOM_INDEX) {
            int[] randlist = blkid_random_map.get(id - RANDOM_INDEX);
            id = randlist[rnd.nextInt(randlist.length)];
        }
        return id;
    }

}
