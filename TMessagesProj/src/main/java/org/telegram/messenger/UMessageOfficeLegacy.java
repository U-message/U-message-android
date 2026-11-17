package org.telegram.messenger;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * U message: the old binary Office formats (Word 97-2003 .doc, Excel .xls, PowerPoint .ppt). They live in an
 * OLE compound file; we read the text, cells and slide texts out of it. Edited .doc and .xls files are saved as
 * new .docx and .xlsx files, since rewriting the binary formats safely is out of reach without a full library.
 */
final class UMessageOfficeLegacy {

    private static final long MAX_FILE = 64L * 1024 * 1024;
    private static final int MAX_ROWS = 3000;
    private static final int MAX_COLS = 150;

    private UMessageOfficeLegacy() {
    }

    // ===================================================================== byte helpers

    static int u8(byte[] d, int o) {
        return d[o] & 0xff;
    }

    static int u16(byte[] d, int o) {
        return (d[o] & 0xff) | (d[o + 1] & 0xff) << 8;
    }

    static int s16(byte[] d, int o) {
        return (short) u16(d, o);
    }

    static int u32(byte[] d, int o) {
        return (d[o] & 0xff) | (d[o + 1] & 0xff) << 8 | (d[o + 2] & 0xff) << 16 | (d[o + 3] & 0xff) << 24;
    }

    static double f64(byte[] d, int o) {
        long bits = 0;
        for (int i = 7; i >= 0; i--) bits = (bits << 8) | (d[o + i] & 0xff);
        return Double.longBitsToDouble(bits);
    }

    static byte[] readFile(File file) throws IOException {
        if (file.length() > MAX_FILE) throw new IOException("too large");
        byte[] data = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            int off = 0, n;
            while (off < data.length && (n = in.read(data, off, data.length - off)) > 0) off += n;
        }
        return data;
    }

    static boolean isCompoundFile(byte[] head) {
        return head.length >= 8 && (head[0] & 0xff) == 0xD0 && (head[1] & 0xff) == 0xCF && (head[2] & 0xff) == 0x11 && (head[3] & 0xff) == 0xE0
                && (head[4] & 0xff) == 0xA1 && (head[5] & 0xff) == 0xB1 && (head[6] & 0xff) == 0x1A && (head[7] & 0xff) == 0xE1;
    }

    // ===================================================================== OLE compound file

    static final class Cfb {
        final byte[] d;
        final int sectorSize;
        int[] fat;
        int[] miniFat;
        byte[] miniStream;
        final int cutoff;
        final ArrayList<String> names = new ArrayList<>();
        final ArrayList<int[]> entries = new ArrayList<>(); // type, start, size

        Cfb(byte[] d) throws Exception {
            this.d = d;
            if (!isCompoundFile(d)) throw new UMessageOfficeDocument.UnsupportedException("not a compound file");
            sectorSize = 1 << u16(d, 0x1E);
            if (sectorSize != 512 && sectorSize != 4096) throw new UMessageOfficeDocument.UnsupportedException("sector size");
            cutoff = u32(d, 0x38);
            ArrayList<Integer> fatSectors = new ArrayList<>();
            for (int i = 0; i < 109; i++) {
                int v = u32(d, 0x4C + i * 4);
                if (v >= 0) fatSectors.add(v);
            }
            int difat = u32(d, 0x44);
            int numDifat = u32(d, 0x48);
            for (int k = 0; k < numDifat && difat >= 0 && valid(difat); k++) {
                int off = offset(difat);
                for (int i = 0; i < sectorSize / 4 - 1; i++) {
                    int v = u32(d, off + i * 4);
                    if (v >= 0) fatSectors.add(v);
                }
                difat = u32(d, off + sectorSize - 4);
            }
            fat = new int[fatSectors.size() * sectorSize / 4];
            int n = 0;
            for (int sector : fatSectors) {
                if (!valid(sector)) {
                    n += sectorSize / 4;
                    continue;
                }
                int off = offset(sector);
                for (int i = 0; i < sectorSize / 4; i++) fat[n++] = u32(d, off + i * 4);
            }
            byte[] dir = chain(u32(d, 0x30), Long.MAX_VALUE);
            for (int o = 0; o + 128 <= dir.length; o += 128) {
                int nameLen = u16(dir, o + 0x40);
                StringBuilder name = new StringBuilder();
                for (int i = 0; i + 1 < Math.min(nameLen, 64) - 1; i += 2) name.append((char) u16(dir, o + i));
                names.add(name.toString());
                entries.add(new int[]{u8(dir, o + 0x42), u32(dir, o + 0x74), u32(dir, o + 0x78)});
            }
            if (!entries.isEmpty()) {
                int[] root = entries.get(0);
                miniStream = chain(root[1], root[2] & 0xffffffffL);
                byte[] mf = chain(u32(d, 0x3C), Long.MAX_VALUE);
                miniFat = new int[mf.length / 4];
                for (int i = 0; i < miniFat.length; i++) miniFat[i] = u32(mf, i * 4);
            }
        }

        boolean valid(int sector) {
            return sector >= 0 && (long) (sector + 1) * sectorSize < d.length;
        }

        int offset(int sector) {
            return (sector + 1) * sectorSize;
        }

        byte[] chain(int start, long limit) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int sector = start;
            int guard = 0;
            while (sector >= 0 && valid(sector) && guard++ <= fat.length && out.size() < limit) {
                int off = offset(sector);
                int len = (int) Math.min(sectorSize, d.length - off);
                out.write(d, off, len);
                sector = sector < fat.length ? fat[sector] : -2;
            }
            byte[] b = out.toByteArray();
            if (limit < b.length) {
                byte[] t = new byte[(int) limit];
                System.arraycopy(b, 0, t, 0, t.length);
                return t;
            }
            return b;
        }

        byte[] miniChain(int start, int size) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int sector = start;
            int guard = 0;
            while (sector >= 0 && miniFat != null && sector < miniFat.length && guard++ <= miniFat.length && out.size() < size) {
                int off = sector * 64;
                if (off + 64 > miniStream.length) break;
                out.write(miniStream, off, 64);
                sector = miniFat[sector];
            }
            byte[] b = out.toByteArray();
            if (size < b.length) {
                byte[] t = new byte[size];
                System.arraycopy(b, 0, t, 0, size);
                return t;
            }
            return b;
        }

        byte[] stream(String name) {
            for (int i = 0; i < names.size(); i++) {
                int[] e = entries.get(i);
                if (e[0] != 2 || !names.get(i).equalsIgnoreCase(name)) continue;
                long size = e[2] & 0xffffffffL;
                if (size > MAX_FILE) return null;
                if (size < cutoff) return miniChain(e[1], (int) size);
                return chain(e[1], size);
            }
            return null;
        }
    }

    // ===================================================================== Word 97-2003

    static final class DocModel {
        /** Blocks: String[] paragraph {index, text} or ArrayList<ArrayList<ArrayList<String[]>>> table (rows, cells, paragraphs). */
        final ArrayList<Object> blocks = new ArrayList<>();
        int count;

        String[] paragraph(String text) {
            return new String[]{String.valueOf(count++), text};
        }
    }

    static DocModel readDoc(File file) throws Exception {
        Cfb cfb = new Cfb(readFile(file));
        byte[] wd = cfb.stream("WordDocument");
        if (wd == null || wd.length < 0x200 || u16(wd, 0) != 0xA5EC) throw new UMessageOfficeDocument.UnsupportedException("not word 97");
        int flags = u16(wd, 0x0A);
        if ((flags & 0x0100) != 0) throw new UMessageOfficeDocument.UnsupportedException("encrypted");
        byte[] table = cfb.stream((flags & 0x0200) != 0 ? "1Table" : "0Table");
        int csw = u16(wd, 32);
        int lw = 32 + 2 + csw * 2;
        int cslw = u16(wd, lw);
        int rgLw = lw + 2;
        int ccpText = u32(wd, rgLw + 12);
        int rgFcLcb = rgLw + cslw * 4 + 2;
        int fcClx = u32(wd, rgFcLcb + 33 * 8);
        int lcbClx = u32(wd, rgFcLcb + 33 * 8 + 4);
        if (table == null || lcbClx <= 0 || fcClx < 0 || fcClx + lcbClx > table.length) throw new UMessageOfficeDocument.UnsupportedException("no piece table");
        StringBuilder text = new StringBuilder();
        Charset ansi = Charset.forName("windows-1252");
        int i = fcClx, end = fcClx + lcbClx;
        while (i < end) {
            int type = table[i];
            if (type == 1) {
                i += 3 + Math.max(0, s16(table, i + 1));
            } else if (type == 2) {
                int lcb = u32(table, i + 1);
                int start = i + 5;
                int n = (lcb - 4) / 12;
                int pcds = start + (n + 1) * 4;
                for (int k = 0; k < n && text.length() < ccpText; k++) {
                    int cpStart = u32(table, start + k * 4), cpEnd = u32(table, start + (k + 1) * 4);
                    int fcRaw = u32(table, pcds + k * 8 + 2);
                    boolean compressed = (fcRaw & 0x40000000) != 0;
                    int fc = fcRaw & 0x3FFFFFFF;
                    int len = Math.max(0, cpEnd - cpStart);
                    if (compressed) {
                        fc /= 2;
                        if (fc < 0 || fc + len > wd.length) break;
                        text.append(new String(wd, fc, len, ansi));
                    } else {
                        if (fc < 0 || fc + len * 2 > wd.length) break;
                        for (int c = 0; c < len; c++) text.append((char) u16(wd, fc + c * 2));
                    }
                }
                break;
            } else {
                break;
            }
        }
        if (text.length() > ccpText && ccpText > 0) text.setLength(ccpText);
        return buildDoc(text);
    }

    /** Fields keep their shown result; cell marks (0x07) build tables, two in a row end a table row. */
    static DocModel buildDoc(CharSequence raw) {
        DocModel model = new DocModel();
        ArrayList<Boolean> fields = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        ArrayList<String> pending = new ArrayList<>();
        ArrayList<ArrayList<String[]>> row = new ArrayList<>();
        ArrayList<ArrayList<ArrayList<String[]>>> table = null;
        boolean lastWasMark = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == 0x13) {
                fields.add(false);
                continue;
            }
            if (c == 0x14) {
                if (!fields.isEmpty()) fields.set(fields.size() - 1, true);
                continue;
            }
            if (c == 0x15) {
                if (!fields.isEmpty()) fields.remove(fields.size() - 1);
                continue;
            }
            if (fields.contains(false)) continue;
            if (c == '\r' || c == 0x0C || c == 0x0E) {
                if (!row.isEmpty()) {
                    pending.add(cur.toString());
                } else {
                    if (table != null) {
                        model.blocks.add(table);
                        table = null;
                    }
                    if (c == '\r' || cur.length() > 0) model.blocks.add(model.paragraph(cur.toString()));
                }
                cur.setLength(0);
                lastWasMark = false;
                continue;
            }
            if (c == 0x07) {
                if (lastWasMark && cur.length() == 0 && pending.isEmpty()) {
                    if (table == null) table = new ArrayList<>();
                    table.add(row);
                    row = new ArrayList<>();
                    lastWasMark = false;
                    continue;
                }
                if (table == null && row.isEmpty()) table = new ArrayList<>();
                ArrayList<String[]> cell = new ArrayList<>();
                for (String p : pending) cell.add(model.paragraph(p));
                cell.add(model.paragraph(cur.toString()));
                row.add(cell);
                pending.clear();
                cur.setLength(0);
                lastWasMark = true;
                continue;
            }
            lastWasMark = false;
            switch (c) {
                case 0x0B: cur.append('\n'); break;
                case 0x1E: cur.append('-'); break;
                case 0x01: case 0x02: case 0x03: case 0x04: case 0x05: case 0x08: case 0x1F: break;
                default:
                    if (c >= 0x20 || c == '\t') cur.append(c);
            }
        }
        if (!row.isEmpty()) {
            if (table == null) table = new ArrayList<>();
            table.add(row);
        }
        if (table != null) model.blocks.add(table);
        for (String p : pending) model.blocks.add(model.paragraph(p));
        if (cur.length() > 0) model.blocks.add(model.paragraph(cur.toString()));
        return model;
    }

    static void renderDoc(File file, StringBuilder out) throws Exception {
        DocModel model = readDoc(file);
        out.append("<div class=\"paper\">");
        for (Object block : model.blocks) {
            if (block instanceof String[]) {
                docParagraph((String[]) block, out);
            } else {
                @SuppressWarnings("unchecked")
                ArrayList<ArrayList<ArrayList<String[]>>> rows = (ArrayList<ArrayList<ArrayList<String[]>>>) block;
                out.append("<table>");
                for (ArrayList<ArrayList<String[]>> r : rows) {
                    out.append("<tr>");
                    for (ArrayList<String[]> cell : r) {
                        out.append("<td>");
                        for (String[] p : cell) docParagraph(p, out);
                        out.append("</td>");
                    }
                    out.append("</tr>");
                }
                out.append("</table>");
            }
        }
        out.append("</div>");
    }

    static void docParagraph(String[] p, StringBuilder out) {
        out.append("<p data-e=\"d:").append(p[0]).append("\">");
        if (p[1].isEmpty()) {
            out.append("<br>");
        } else {
            out.append(UMessageOfficeDocument.esc(p[1]).replace("\n", "<br>").replace("\t", "<span class=\"tab\">\t</span>"));
        }
        out.append("</p>");
    }

    static void saveDocAsDocx(File source, HashMap<String, String> edits, File out) throws Exception {
        DocModel model = readDoc(source);
        StringBuilder body = new StringBuilder();
        for (Object block : model.blocks) {
            if (block instanceof String[]) {
                body.append(wordParagraph((String[]) block, edits));
            } else {
                @SuppressWarnings("unchecked")
                ArrayList<ArrayList<ArrayList<String[]>>> rows = (ArrayList<ArrayList<ArrayList<String[]>>>) block;
                int cols = 1;
                for (ArrayList<ArrayList<String[]>> r : rows) cols = Math.max(cols, r.size());
                body.append("<w:tbl><w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/><w:tblBorders>");
                for (String side : new String[]{"top", "left", "bottom", "right", "insideH", "insideV"}) {
                    body.append("<w:").append(side).append(" w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"999999\"/>");
                }
                body.append("</w:tblBorders></w:tblPr><w:tblGrid>");
                for (int c = 0; c < cols; c++) body.append("<w:gridCol w:w=\"").append(9000 / cols).append("\"/>");
                body.append("</w:tblGrid>");
                for (ArrayList<ArrayList<String[]>> r : rows) {
                    body.append("<w:tr>");
                    for (int c = 0; c < cols; c++) {
                        body.append("<w:tc><w:tcPr><w:tcW w:w=\"").append(9000 / cols).append("\" w:type=\"dxa\"/></w:tcPr>");
                        if (c < r.size()) {
                            for (String[] p : r.get(c)) body.append(wordParagraph(p, edits));
                        } else {
                            body.append("<w:p/>");
                        }
                        body.append("</w:tc>");
                    }
                    body.append("</w:tr>");
                }
                body.append("</w:tbl>");
            }
        }
        String document = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                + body
                + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"850\" w:bottom=\"1134\" w:left=\"1701\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr>"
                + "</w:body></w:document>";
        LinkedMap parts = new LinkedMap();
        parts.put("[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "</Types>");
        parts.put("_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                + "</Relationships>");
        parts.put("word/document.xml", document);
        writeParts(parts, out);
    }

    static String wordParagraph(String[] p, HashMap<String, String> edits) {
        String text = edits.containsKey("d:" + p[0]) ? edits.get("d:" + p[0]) : p[1];
        StringBuilder sb = new StringBuilder("<w:p>");
        if (!text.isEmpty()) {
            sb.append("<w:r>");
            StringBuilder segment = new StringBuilder();
            for (int i = 0; i <= text.length(); i++) {
                char c = i < text.length() ? text.charAt(i) : 0;
                if (i == text.length() || c == '\n' || c == '\t') {
                    if (segment.length() > 0) {
                        sb.append("<w:t xml:space=\"preserve\">").append(UMessageOfficeDocument.xmlEsc(UMessageOfficeDocument.clean(segment.toString()))).append("</w:t>");
                        segment.setLength(0);
                    }
                    if (c == '\n') sb.append("<w:br/>");
                    else if (c == '\t') sb.append("<w:tab/>");
                } else {
                    segment.append(c);
                }
            }
            sb.append("</w:r>");
        }
        return sb.append("</w:p>").toString();
    }

    // ===================================================================== Excel 97-2003

    static final class Cell {
        String text = "";
        boolean number;
        double value;
        boolean formula;
        boolean bold;
        boolean date;
    }

    static final class Sheet {
        String name;
        final HashMap<Long, Cell> cells = new HashMap<>();
        final ArrayList<int[]> merges = new ArrayList<>(); // r1, r2, c1, c2 (1-based)
        final HashMap<Integer, Float> widths = new HashMap<>();
        int maxRow, maxCol;
        boolean truncated;
    }

    /** SST and CONTINUE records together; a string split across them restarts with a new flags byte. */
    static final class Segments {
        final ArrayList<byte[]> s = new ArrayList<>();
        int i, p;

        boolean ensure() {
            while (i < s.size() && p >= s.get(i).length) {
                i++;
                p = 0;
            }
            return i < s.size();
        }

        int u8() {
            if (!ensure()) throw new IndexOutOfBoundsException();
            return s.get(i)[p++] & 0xff;
        }

        int u16() {
            return u8() | u8() << 8;
        }

        int u32() {
            return u16() | u16() << 16;
        }

        void skip(long n) {
            while (n > 0 && ensure()) {
                int take = (int) Math.min(n, s.get(i).length - p);
                p += take;
                n -= take;
            }
        }

        String chars(int cch, boolean high) {
            StringBuilder sb = new StringBuilder(cch);
            while (sb.length() < cch) {
                if (i < s.size() && p >= s.get(i).length) {
                    i++;
                    p = 0;
                    if (i >= s.size()) break;
                    high = (s.get(i)[p++] & 1) != 0;
                    continue;
                }
                if (i >= s.size()) break;
                sb.append(high ? (char) u16() : (char) u8());
            }
            return sb.toString();
        }
    }

    static String xlString16(byte[] d, int o) {
        int cch = u16(d, o);
        boolean high = (u8(d, o + 2) & 1) != 0;
        return chars(d, o + 3, cch, high);
    }

    static String chars(byte[] d, int o, int cch, boolean high) {
        StringBuilder sb = new StringBuilder(cch);
        for (int k = 0; k < cch; k++) {
            int at = o + (high ? k * 2 : k);
            if (at + (high ? 1 : 0) >= d.length) break;
            sb.append(high ? (char) u16(d, at) : (char) u8(d, at));
        }
        return sb.toString();
    }

    static double rk(int rk) {
        double v;
        if ((rk & 2) != 0) {
            v = rk >> 2;
        } else {
            v = Double.longBitsToDouble(((long) (rk & 0xFFFFFFFC)) << 32);
        }
        return (rk & 1) != 0 ? v / 100 : v;
    }

    static ArrayList<Sheet> readXls(File file) throws Exception {
        Cfb cfb = new Cfb(readFile(file));
        byte[] wb = cfb.stream("Workbook");
        if (wb == null) throw new UMessageOfficeDocument.UnsupportedException(cfb.stream("Book") != null ? "excel 5" : "no workbook");
        ArrayList<String> sst = new ArrayList<>();
        HashMap<Integer, String> formats = new HashMap<>();
        ArrayList<int[]> xfs = new ArrayList<>(); // ifmt, ifnt
        ArrayList<Boolean> fontsBold = new ArrayList<>();
        ArrayList<Object[]> sheetsInfo = new ArrayList<>(); // pos, name, visible
        int o = 0;
        boolean first = true;
        while (o + 4 <= wb.length) {
            int type = u16(wb, o), len = u16(wb, o + 2);
            int data = o + 4;
            if (data + len > wb.length) break;
            o = data + len;
            if (first) {
                first = false;
                if (type != 0x809) throw new UMessageOfficeDocument.UnsupportedException("no BOF");
                if (u16(wb, data) != 0x600) throw new UMessageOfficeDocument.UnsupportedException("old BIFF");
                continue;
            }
            if (type == 0x0A) break; // end of globals
            switch (type) {
                case 0x2F: throw new UMessageOfficeDocument.UnsupportedException("encrypted");
                case 0x85: {
                    int pos = u32(wb, data);
                    int state = u8(wb, data + 4) & 3, dt = u8(wb, data + 5);
                    int cch = u8(wb, data + 6);
                    boolean high = (u8(wb, data + 7) & 1) != 0;
                    if (dt == 0) sheetsInfo.add(new Object[]{pos, chars(wb, data + 8, cch, high), state == 0});
                    break;
                }
                case 0x41E:
                    formats.put(u16(wb, data), xlString16(wb, data + 2));
                    break;
                case 0xE0:
                    xfs.add(new int[]{u16(wb, data + 2), u16(wb, data)});
                    break;
                case 0x31:
                    fontsBold.add(u16(wb, data + 6) >= 700);
                    break;
                case 0xFC: {
                    Segments seg = new Segments();
                    byte[] first0 = new byte[len];
                    System.arraycopy(wb, data, first0, 0, len);
                    seg.s.add(first0);
                    while (o + 4 <= wb.length && u16(wb, o) == 0x3C) {
                        int cl = u16(wb, o + 2);
                        if (o + 4 + cl > wb.length) break;
                        byte[] part = new byte[cl];
                        System.arraycopy(wb, o + 4, part, 0, cl);
                        seg.s.add(part);
                        o += 4 + cl;
                    }
                    try {
                        seg.u32();
                        int unique = seg.u32();
                        for (int k = 0; k < unique; k++) {
                            int cch = seg.u16();
                            int fl = seg.u8();
                            int runs = (fl & 8) != 0 ? seg.u16() : 0;
                            long ext = (fl & 4) != 0 ? seg.u32() & 0xffffffffL : 0;
                            sst.add(seg.chars(cch, (fl & 1) != 0));
                            seg.skip(runs * 4L);
                            seg.skip(ext);
                        }
                    } catch (IndexOutOfBoundsException ignore) {
                        // a cut SST: keep the strings read so far
                    }
                    break;
                }
                default:
                    break;
            }
        }
        ArrayList<Sheet> sheets = new ArrayList<>();
        for (Object[] info : sheetsInfo) {
            if (!(Boolean) info[2]) continue;
            Sheet sheet = new Sheet();
            sheet.name = (String) info[1];
            try {
                readSheet(wb, (Integer) info[0], sheet, sst, formats, xfs, fontsBold);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            sheets.add(sheet);
        }
        if (sheets.isEmpty()) throw new UMessageOfficeDocument.UnsupportedException("no sheets");
        return sheets;
    }

    static void readSheet(byte[] wb, int pos, Sheet sheet, ArrayList<String> sst, HashMap<Integer, String> formats,
                          ArrayList<int[]> xfs, ArrayList<Boolean> fontsBold) {
        int o = pos;
        Cell pendingString = null;
        boolean first = true;
        while (o + 4 <= wb.length) {
            int type = u16(wb, o), len = u16(wb, o + 2);
            int d = o + 4;
            if (d + len > wb.length) break;
            o = d + len;
            if (first) {
                first = false;
                if (type != 0x809) return;
                continue;
            }
            if (type == 0x0A) break;
            switch (type) {
                case 0x203: // NUMBER
                    number(sheet, u16(wb, d), u16(wb, d + 2), u16(wb, d + 4), f64(wb, d + 6), formats, xfs, fontsBold);
                    break;
                case 0x27E: // RK
                    number(sheet, u16(wb, d), u16(wb, d + 2), u16(wb, d + 4), rk(u32(wb, d + 6)), formats, xfs, fontsBold);
                    break;
                case 0xBD: { // MULRK
                    int row = u16(wb, d), col = u16(wb, d + 2);
                    int count = (len - 6) / 6;
                    for (int k = 0; k < count; k++) {
                        number(sheet, row, col + k, u16(wb, d + 4 + k * 6), rk(u32(wb, d + 6 + k * 6)), formats, xfs, fontsBold);
                    }
                    break;
                }
                case 0xFD: { // LABELSST
                    int idx = u32(wb, d + 6);
                    Cell c = put(sheet, u16(wb, d), u16(wb, d + 2), u16(wb, d + 4), xfs, fontsBold);
                    if (c != null) c.text = idx >= 0 && idx < sst.size() ? sst.get(idx) : "";
                    break;
                }
                case 0x204: { // LABEL
                    Cell c = put(sheet, u16(wb, d), u16(wb, d + 2), u16(wb, d + 4), xfs, fontsBold);
                    if (c != null) c.text = xlString16(wb, d + 6);
                    break;
                }
                case 0x205: { // BOOLERR
                    Cell c = put(sheet, u16(wb, d), u16(wb, d + 2), u16(wb, d + 4), xfs, fontsBold);
                    if (c != null) {
                        int v = u8(wb, d + 6);
                        c.text = u8(wb, d + 7) == 0 ? (v != 0 ? "TRUE" : "FALSE") : "#ERROR";
                    }
                    break;
                }
                case 0x06: { // FORMULA: show the stored result
                    int row = u16(wb, d), col = u16(wb, d + 2), xf = u16(wb, d + 4);
                    pendingString = null;
                    if (u16(wb, d + 12) == 0xFFFF) {
                        int kind = u8(wb, d + 6);
                        Cell c = put(sheet, row, col, xf, xfs, fontsBold);
                        if (c == null) break;
                        c.formula = true;
                        if (kind == 0) pendingString = c;
                        else if (kind == 1) c.text = u8(wb, d + 8) != 0 ? "TRUE" : "FALSE";
                        else if (kind == 2) c.text = "#ERROR";
                    } else {
                        Cell c = number(sheet, row, col, xf, f64(wb, d + 6), formats, xfs, fontsBold);
                        if (c != null) c.formula = true;
                    }
                    break;
                }
                case 0x207: // STRING after a text formula
                    if (pendingString != null) pendingString.text = xlString16(wb, d);
                    pendingString = null;
                    break;
                case 0xE5: { // MERGEDCELLS
                    int count = u16(wb, d);
                    for (int k = 0; k < count && d + 2 + k * 8 + 8 <= d + len; k++) {
                        int b = d + 2 + k * 8;
                        int r1 = u16(wb, b) + 1, r2 = u16(wb, b + 2) + 1, c1 = u16(wb, b + 4) + 1, c2 = u16(wb, b + 6) + 1;
                        if (r1 > MAX_ROWS || c1 > MAX_COLS) continue;
                        sheet.merges.add(new int[]{r1, Math.min(r2, MAX_ROWS), c1, Math.min(c2, MAX_COLS)});
                    }
                    break;
                }
                case 0x7D: { // COLINFO
                    int c1 = u16(wb, d) + 1, c2 = Math.min(u16(wb, d + 2) + 1, MAX_COLS);
                    float w = (u16(wb, d + 8) & 1) != 0 ? 0 : u16(wb, d + 4) / 256f;
                    for (int c = c1; c <= c2; c++) sheet.widths.put(c, w);
                    break;
                }
                default:
                    break;
            }
        }
    }

    static Cell put(Sheet sheet, int row0, int col0, int xf, ArrayList<int[]> xfs, ArrayList<Boolean> fontsBold) {
        int row = row0 + 1, col = col0 + 1;
        if (row > MAX_ROWS || col > MAX_COLS) {
            sheet.truncated = true;
            return null;
        }
        Cell c = new Cell();
        if (xf >= 0 && xf < xfs.size()) {
            int font = xfs.get(xf)[1];
            if (font >= 4) font--;
            c.bold = font >= 0 && font < fontsBold.size() && fontsBold.get(font);
        }
        sheet.cells.put(UMessageOfficeDocument.key(row, col), c);
        sheet.maxRow = Math.max(sheet.maxRow, row);
        sheet.maxCol = Math.max(sheet.maxCol, col);
        return c;
    }

    static Cell number(Sheet sheet, int row, int col, int xf, double value, HashMap<Integer, String> formats,
                       ArrayList<int[]> xfs, ArrayList<Boolean> fontsBold) {
        Cell c = put(sheet, row, col, xf, xfs, fontsBold);
        if (c == null) return null;
        int fmt = xf >= 0 && xf < xfs.size() ? xfs.get(xf)[0] : 0;
        String code = formats.get(fmt);
        if (code == null) code = UMessageOfficeDocument.builtinFormat(fmt);
        c.number = true;
        c.value = value;
        c.text = UMessageOfficeDocument.applyFormat(value, code);
        c.date = !c.text.equals(UMessageOfficeDocument.general(value)) && (c.text.contains(":") || c.text.matches("\\d{2}\\.\\d{2}\\.\\d{4}.*"));
        return c;
    }

    static void renderXls(File file, StringBuilder out) throws Exception {
        ArrayList<Sheet> sheets = readXls(file);
        if (sheets.size() > 1) {
            out.append("<div class=\"tabs\">");
            for (int i = 0; i < sheets.size(); i++) {
                out.append("<b").append(i == 0 ? " class=\"on\"" : "").append(" onclick=\"UM.sheet(").append(i).append(")\">")
                        .append(UMessageOfficeDocument.esc(sheets.get(i).name)).append("</b>");
            }
            out.append("</div>");
        }
        for (int i = 0; i < sheets.size(); i++) {
            out.append("<div class=\"sheet").append(i == 0 ? " on" : "").append("\">");
            renderGrid(sheets.get(i), i, out);
            out.append("</div>");
        }
    }

    static void renderGrid(Sheet sheet, int index, StringBuilder out) {
        HashMap<Long, int[]> spans = new HashMap<>();
        HashSet<Long> covered = new HashSet<>();
        int maxRow = sheet.maxRow, maxCol = sheet.maxCol;
        for (int[] m : sheet.merges) {
            spans.put(UMessageOfficeDocument.key(m[0], m[2]), new int[]{m[1] - m[0] + 1, m[3] - m[2] + 1});
            for (int r = m[0]; r <= m[1]; r++) {
                for (int c = m[2]; c <= m[3]; c++) {
                    if (r != m[0] || c != m[2]) covered.add(UMessageOfficeDocument.key(r, c));
                }
            }
            maxRow = Math.max(maxRow, m[1]);
            maxCol = Math.max(maxCol, m[3]);
        }
        maxRow = Math.min(MAX_ROWS, Math.max(maxRow + 5, 20));
        maxCol = Math.min(MAX_COLS, Math.max(maxCol + 2, 6));
        out.append("<table class=\"grid\"><colgroup><col style=\"width:36px\">");
        for (int c = 1; c <= maxCol; c++) {
            Float w = sheet.widths.get(c);
            out.append("<col style=\"width:").append(Math.round((w == null ? 8.43f : w) * 7 + 5)).append("px\">");
        }
        out.append("</colgroup><thead><tr><th class=\"c\"></th>");
        for (int c = 1; c <= maxCol; c++) out.append("<th>").append(UMessageOfficeDocument.letters(c)).append("</th>");
        out.append("</tr></thead><tbody>");
        for (int r = 1; r <= maxRow; r++) {
            out.append("<tr><th>").append(r).append("</th>");
            for (int c = 1; c <= maxCol; c++) {
                long k = UMessageOfficeDocument.key(r, c);
                if (covered.contains(k)) continue;
                Cell cell = sheet.cells.get(k);
                out.append("<td");
                int[] span = spans.get(k);
                if (span != null) {
                    if (span[0] > 1) out.append(" rowspan=\"").append(span[0]).append('"');
                    if (span[1] > 1) out.append(" colspan=\"").append(span[1]).append('"');
                }
                String cls = "";
                if (cell != null && cell.number) cls += " n";
                if (cell != null && cell.formula) cls += " f";
                if (!cls.isEmpty()) out.append(" class=\"").append(cls.trim()).append('"');
                if (cell != null && cell.bold) out.append(" style=\"font-weight:bold\"");
                if (cell == null || !cell.formula) {
                    out.append(" data-e=\"x:").append(index).append(':').append(UMessageOfficeDocument.letters(c)).append(r).append('"');
                }
                out.append('>');
                if (cell != null) out.append(UMessageOfficeDocument.esc(cell.text));
                out.append("</td>");
            }
            out.append("</tr>");
        }
        out.append("</tbody></table>");
        if (sheet.truncated) {
            out.append("<div class=\"note\">").append(UMessageOfficeDocument.esc(LocaleController.formatString(R.string.UMessageOfficeTruncated, MAX_ROWS, MAX_COLS))).append("</div>");
        }
    }

    static void saveXlsAsXlsx(File source, HashMap<String, String> edits, File out) throws Exception {
        ArrayList<Sheet> sheets = readXls(source);
        for (java.util.Map.Entry<String, String> e : edits.entrySet()) {
            String[] parts = e.getKey().split(":");
            if (parts.length != 3 || !parts[0].equals("x")) continue;
            int index = Integer.parseInt(parts[1]);
            if (index < 0 || index >= sheets.size()) continue;
            Sheet sheet = sheets.get(index);
            int row = UMessageOfficeDocument.rowOf(parts[2]), col = UMessageOfficeDocument.colOf(parts[2]);
            Cell c = sheet.cells.get(UMessageOfficeDocument.key(row, col));
            if (c == null) {
                c = new Cell();
                sheet.cells.put(UMessageOfficeDocument.key(row, col), c);
            }
            String v = e.getValue().trim();
            c.formula = false;
            c.date = false;
            c.text = e.getValue();
            c.number = v.matches("-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?");
            if (c.number) c.value = Double.parseDouble(v);
        }
        LinkedMap parts = new LinkedMap();
        StringBuilder types = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
        StringBuilder workbook = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
        StringBuilder rels = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        HashSet<String> usedNames = new HashSet<>();
        for (int i = 0; i < sheets.size(); i++) {
            Sheet sheet = sheets.get(i);
            int n = i + 1;
            types.append("<Override PartName=\"/xl/worksheets/sheet").append(n).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
            String name = sheetName(sheet.name, n, usedNames);
            workbook.append("<sheet name=\"").append(UMessageOfficeDocument.xmlEsc(name)).append("\" sheetId=\"").append(n).append("\" r:id=\"rId").append(n).append("\"/>");
            rels.append("<Relationship Id=\"rId").append(n).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet").append(n).append(".xml\"/>");
            parts.put("xl/worksheets/sheet" + n + ".xml", sheetXml(sheet));
        }
        types.append("</Types>");
        workbook.append("</sheets></workbook>");
        rels.append("<Relationship Id=\"rIdStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>");
        parts.put("[Content_Types].xml", types.toString());
        parts.put("_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "</Relationships>");
        parts.put("xl/workbook.xml", workbook.toString());
        parts.put("xl/_rels/workbook.xml.rels", rels.toString());
        parts.put("xl/styles.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>"
                + "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>"
                + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
                + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                + "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/><xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/></cellXfs>"
                + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
                + "</styleSheet>");
        writeParts(parts, out);
    }

    static String sheetName(String name, int n, HashSet<String> used) {
        String s = name == null ? "" : name.replaceAll("[\\[\\]:*?/\\\\]", "_").trim();
        if (s.length() > 31) s = s.substring(0, 31);
        if (s.isEmpty()) s = "Sheet" + n;
        String base = s;
        int k = 2;
        while (used.contains(s.toLowerCase(Locale.ROOT))) {
            String suffix = " (" + k++ + ")";
            s = (base.length() + suffix.length() > 31 ? base.substring(0, 31 - suffix.length()) : base) + suffix;
        }
        used.add(s.toLowerCase(Locale.ROOT));
        return s;
    }

    static String sheetXml(Sheet sheet) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        if (!sheet.widths.isEmpty()) {
            sb.append("<cols>");
            ArrayList<Integer> cols = new ArrayList<>(sheet.widths.keySet());
            java.util.Collections.sort(cols);
            for (int c : cols) {
                float w = sheet.widths.get(c);
                sb.append("<col min=\"").append(c).append("\" max=\"").append(c).append("\" width=\"").append(w <= 0 ? 8.43f : w).append('"');
                if (w <= 0) sb.append(" hidden=\"1\"");
                sb.append(" customWidth=\"1\"/>");
            }
            sb.append("</cols>");
        }
        sb.append("<sheetData>");
        // rows in order, cells in column order
        java.util.TreeMap<Integer, java.util.TreeMap<Integer, Cell>> rows = new java.util.TreeMap<>();
        for (java.util.Map.Entry<Long, Cell> e : sheet.cells.entrySet()) {
            int r = (int) (e.getKey() >> 20), c = (int) (e.getKey() & 0xFFFFF);
            java.util.TreeMap<Integer, Cell> row = rows.get(r);
            if (row == null) rows.put(r, row = new java.util.TreeMap<>());
            row.put(c, e.getValue());
        }
        for (java.util.Map.Entry<Integer, java.util.TreeMap<Integer, Cell>> row : rows.entrySet()) {
            sb.append("<row r=\"").append(row.getKey()).append("\">");
            for (java.util.Map.Entry<Integer, Cell> e : row.getValue().entrySet()) {
                Cell c = e.getValue();
                String ref = UMessageOfficeDocument.letters(e.getKey()) + row.getKey();
                String style = c.bold ? " s=\"1\"" : "";
                if (c.number && !c.date) {
                    sb.append("<c r=\"").append(ref).append('"').append(style).append("><v>").append(plain(c.value)).append("</v></c>");
                } else if (!c.text.isEmpty()) {
                    sb.append("<c r=\"").append(ref).append('"').append(style).append(" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                            .append(UMessageOfficeDocument.xmlEsc(UMessageOfficeDocument.clean(c.text))).append("</t></is></c>");
                }
            }
            sb.append("</row>");
        }
        sb.append("</sheetData>");
        if (!sheet.merges.isEmpty()) {
            sb.append("<mergeCells count=\"").append(sheet.merges.size()).append("\">");
            for (int[] m : sheet.merges) {
                sb.append("<mergeCell ref=\"").append(UMessageOfficeDocument.letters(m[2])).append(m[0]).append(':')
                        .append(UMessageOfficeDocument.letters(m[3])).append(m[1]).append("\"/>");
            }
            sb.append("</mergeCells>");
        }
        return sb.append("</worksheet>").toString();
    }

    static String plain(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "0";
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    // ===================================================================== PowerPoint 97-2003

    static final class Slide {
        final ArrayList<int[]> types = new ArrayList<>();
        final ArrayList<String> texts = new ArrayList<>();
        int persistRef = -1;

        void add(int type, String text) {
            if (text == null || text.trim().isEmpty() || texts.contains(text)) return;
            types.add(new int[]{type});
            texts.add(text);
        }
    }

    static void renderPpt(File file, StringBuilder out) throws Exception {
        Cfb cfb = new Cfb(readFile(file));
        byte[] doc = cfb.stream("PowerPoint Document");
        if (doc == null) throw new UMessageOfficeDocument.UnsupportedException("no presentation");
        HashMap<Integer, Integer> persist = new HashMap<>();
        int docOffset = -1;
        try {
            byte[] user = cfb.stream("Current User");
            int edit = user != null && user.length >= 20 ? u32(user, 16) : -1;
            int guard = 0;
            boolean newest = true;
            while (edit > 0 && edit + 32 <= doc.length && guard++ < 1000) {
                if (u16(doc, edit + 2) != 0x0FF5) break;
                int lastEdit = u32(doc, edit + 16);
                int dir = u32(doc, edit + 20);
                if (newest) {
                    int docRef = u32(doc, edit + 24);
                    newest = false;
                    readPersist(doc, dir, persist);
                    Integer off = persist.get(docRef);
                    docOffset = off == null ? -1 : off;
                } else {
                    readPersist(doc, dir, persist);
                }
                edit = lastEdit;
            }
            if (docOffset < 0) {
                // the newest edit may have been read before the older directories filled the map
                Integer off = persist.get(u32(doc, (user != null ? u32(user, 16) : 0) + 24));
                docOffset = off == null ? -1 : off;
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        float ratio = 0.75f;
        ArrayList<Slide> slides = new ArrayList<>();
        int listOffset = -1, listEnd = -1;
        if (docOffset >= 0 && docOffset + 8 <= doc.length && u16(doc, docOffset + 2) == 0x03E8) {
            int end = Math.min(doc.length, docOffset + 8 + u32(doc, docOffset + 4));
            for (int o = docOffset + 8; o + 8 <= end; ) {
                int verInst = u16(doc, o), type = u16(doc, o + 2), len = u32(doc, o + 4);
                if (len < 0 || o + 8 + len > doc.length) break;
                if (type == 0x03E9 && len >= 8) {
                    int x = u32(doc, o + 8), y = u32(doc, o + 12);
                    if (x > 0 && y > 0) ratio = (float) y / x;
                }
                if (type == 0x0FF0 && (verInst >> 4) == 0) {
                    listOffset = o + 8;
                    listEnd = o + 8 + len;
                }
                o += 8 + len;
            }
        }
        if (listOffset < 0) {
            // no persist directory to follow: take the first slide list in the stream
            int[] found = findRecord(doc, 0, doc.length, 0x0FF0, 0, 0);
            if (found != null) {
                listOffset = found[0];
                listEnd = found[1];
            }
        }
        if (listOffset >= 0) {
            Slide current = null;
            int textType = 1;
            for (int o = listOffset; o + 8 <= listEnd; ) {
                int type = u16(doc, o + 2), len = u32(doc, o + 4);
                if (len < 0 || o + 8 + len > doc.length) break;
                int d = o + 8;
                if (type == 0x03F3) {
                    current = new Slide();
                    current.persistRef = u32(doc, d);
                    slides.add(current);
                    textType = 1;
                } else if (type == 0x0F9F && len >= 4) {
                    textType = u32(doc, d);
                } else if ((type == 0x0FA0 || type == 0x0FA8) && current != null) {
                    current.add(textType, atomText(doc, type, d, len));
                }
                o += 8 + len;
            }
        }
        for (Slide slide : slides) {
            Integer off = persist.get(slide.persistRef);
            if (off == null || off + 8 > doc.length || u16(doc, off + 2) != 0x03EE) continue;
            collectTexts(doc, off + 8, Math.min(doc.length, off + 8 + u32(doc, off + 4)), slide, 0);
        }
        if (slides.isEmpty()) throw new UMessageOfficeDocument.UnsupportedException("no slides");
        for (int i = 0; i < slides.size(); i++) {
            Slide slide = slides.get(i);
            out.append("<div class=\"pslide\" style=\"min-height:0;aspect-ratio:1/").append(ratio).append("\">");
            for (int k = 0; k < slide.texts.size(); k++) {
                int type = slide.types.get(k)[0];
                boolean title = type == 0 || type == 6;
                String[] lines = slide.texts.get(k).split("\r");
                for (String line : lines) {
                    String html = UMessageOfficeDocument.esc(line).replace("\u000B", "<br>");
                    if (title) out.append("<h2>").append(html).append("</h2>");
                    else out.append("<p>").append(html).append("</p>");
                }
            }
            out.append("</div><div class=\"num\">").append(i + 1).append("</div>");
        }
    }

    static void readPersist(byte[] doc, int dir, HashMap<Integer, Integer> persist) {
        if (dir < 0 || dir + 8 > doc.length || u16(doc, dir + 2) != 0x1772) return;
        int end = Math.min(doc.length, dir + 8 + u32(doc, dir + 4));
        int o = dir + 8;
        while (o + 4 <= end) {
            int info = u32(doc, o);
            int id = info & 0xFFFFF, count = (info >>> 20) & 0xFFF;
            o += 4;
            for (int k = 0; k < count && o + 4 <= end; k++, o += 4) {
                if (!persist.containsKey(id + k)) persist.put(id + k, u32(doc, o));
            }
        }
    }

    static String atomText(byte[] doc, int type, int d, int len) {
        if (type == 0x0FA0) {
            StringBuilder sb = new StringBuilder(len / 2);
            for (int k = 0; k + 1 < len; k += 2) sb.append((char) u16(doc, d + k));
            return sb.toString();
        }
        return new String(doc, d, len, StandardCharsets.ISO_8859_1);
    }

    static void collectTexts(byte[] doc, int start, int end, Slide slide, int depth) {
        if (depth > 12) return;
        int textType = 4;
        for (int o = start; o + 8 <= end; ) {
            int verInst = u16(doc, o), type = u16(doc, o + 2), len = u32(doc, o + 4);
            if (len < 0 || o + 8 + len > end) break;
            if ((verInst & 0xF) == 0xF) {
                collectTexts(doc, o + 8, o + 8 + len, slide, depth + 1);
            } else if (type == 0x0F9F && len >= 4) {
                textType = u32(doc, o + 8);
            } else if (type == 0x0FA0 || type == 0x0FA8) {
                slide.add(textType, atomText(doc, type, o + 8, len));
            }
            o += 8 + len;
        }
    }

    /** {start, end} of the data of the first container of {@code type} and {@code instance}, searched depth first. */
    static int[] findRecord(byte[] doc, int start, int end, int type, int instance, int depth) {
        if (depth > 8) return null;
        for (int o = start; o + 8 <= end; ) {
            int verInst = u16(doc, o), t = u16(doc, o + 2), len = u32(doc, o + 4);
            if (len < 0 || o + 8 + len > end) break;
            if (t == type && (verInst >> 4) == instance) return new int[]{o + 8, o + 8 + len};
            if ((verInst & 0xF) == 0xF) {
                int[] inner = findRecord(doc, o + 8, o + 8 + len, type, instance, depth + 1);
                if (inner != null) return inner;
            }
            o += 8 + len;
        }
        return null;
    }

    // ===================================================================== writing

    static final class LinkedMap extends java.util.LinkedHashMap<String, String> {
    }

    static void writeParts(LinkedMap parts, File out) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            for (java.util.Map.Entry<String, String> e : parts.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
    }
}
