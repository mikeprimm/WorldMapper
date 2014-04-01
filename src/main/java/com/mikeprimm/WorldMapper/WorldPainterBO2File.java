package com.mikeprimm.WorldMapper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

public class WorldPainterBO2File {
    private List<String> lines = new ArrayList<String>();
    
    private List<int[]> idmap = new ArrayList<int[]>();
    
    public void load(File f) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(f), Charset.forName("US-ASCII")));
        try {
            boolean readingMetaData = false, readingData = false;
            String line;
            while ((line = in.readLine()) != null) {
                int idx = lines.size();
                lines.add(line);    // Add line
                if (line.trim().length() == 0) {
                    continue;
                }
                if (readingMetaData) {
                    if (line.equals("[DATA]")) {
                        readingMetaData = false;
                        readingData = true;
                    }
                } 
                else if (readingData) {
                    int p = line.indexOf(':');
                    if (p > 0) {
                        String spec = line.substring(p+1);
                        p = spec.indexOf('.');
                        int blockId, data = 0;
                        if (p == -1) {
                            blockId = Integer.parseInt(spec);
                        } else {
                            blockId = Integer.parseInt(spec.substring(0, p));
                            int p2 = spec.indexOf('#', p + 1);
                            if (p2 == -1) {
                                data = Integer.parseInt(spec.substring(p + 1));
                            } else {
                                data = Integer.parseInt(spec.substring(p + 1, p2));
                                p = spec.indexOf('@', p2 + 1);
                            }
                        }
                        idmap.add(new int[] { idx, blockId, data });
                    }
                } else {
                    if (line.equals("[META]")) {
                        readingMetaData = true;
                    }
                }
            }
        } finally {
            in.close();
        }
    }
    
    public void save(File file) throws IOException {
        OutputStreamWriter out = new OutputStreamWriter(new FileOutputStream(file), Charset.forName("US-ASCII"));

        try {
            for (int i = 0; i < lines.size(); i++) {
                out.write(lines.get(i) + "\r\n");
            }
        }
        finally {
            if (out != null) {
                out.close();
            }
        }
    }
    
    public int dataCount() {
        return idmap.size();
    }
    
    public int getID(int idx) {
        int[] val = idmap.get(idx);
        if (val == null) {
            return 0;
        }
        return val[1];
    }

    public int getData(int idx) {
        int[] val = idmap.get(idx);
        if (val == null) {
            return 0;
        }
        return val[2];
    }

    public void setIDAndData(int idx, int id, int dat) {
        int[] v = idmap.get(idx);
        String line = lines.get(v[0]);
        if (line == null) return;

        int p = line.indexOf(':');
        if (p > 0) {
            String start = line.substring(0, p+1);
            String spec = line.substring(p+1);
            p = spec.indexOf('.');
            String extra = "";
            if (p >= 0) {
                p = spec.indexOf('#', p + 1);
                if (p > 0) {
                    extra = spec.substring(p);
                }
            }
            line = start + id + "." + dat + extra;
            lines.set(v[0], line);
        }
    }
}
