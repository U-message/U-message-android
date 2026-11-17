package org.telegram.messenger;

import android.text.TextUtils;
import android.util.Base64;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * U message: reads Word, Excel, PowerPoint, CSV and plain text files without any library and turns them into
 * one HTML page for the in-app viewer. Edits made on that page are written back by changing only the edited
 * paragraphs and cells in the original XML, so everything we don't understand (charts, macros, comments,
 * formatting of untouched text) is kept byte for byte.
 */
public final class UMessageOfficeDocument {

    public static final int NONE = 0, DOCX = 1, XLSX = 2, PPTX = 3, CSV = 4, TXT = 5, DOC = 6, XLS = 7, PPT = 8;

    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String S = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final String P = "http://schemas.openxmlformats.org/presentationml/2006/main";

    private static final long MAX_PART = 64L * 1024 * 1024;
    private static final long MAX_IMAGE = 6L * 1024 * 1024;
    private static final long MAX_IMAGES_TOTAL = 40L * 1024 * 1024;
    private static final long MAX_TEXT = 8L * 1024 * 1024;
    private static final int MAX_ROWS = 3000;
    private static final int MAX_COLS = 150;

    private UMessageOfficeDocument() {
    }

    public static int kindOf(String name, String mime) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (n.endsWith(".docx") || n.endsWith(".docm") || n.endsWith(".dotx") || m.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document")) return DOCX;
        if (n.endsWith(".xlsx") || n.endsWith(".xlsm") || n.endsWith(".xltx") || m.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) return XLSX;
        if (n.endsWith(".pptx") || n.endsWith(".ppsx") || n.endsWith(".potx") || m.equals("application/vnd.openxmlformats-officedocument.presentationml.presentation")) return PPTX;
        if (n.endsWith(".doc") || n.endsWith(".dot") || m.equals("application/msword")) return DOC;
        if (n.endsWith(".xls") || n.endsWith(".xlt") || m.equals("application/vnd.ms-excel")) return XLS;
        if (n.endsWith(".ppt") || n.endsWith(".pps") || n.endsWith(".pot") || m.equals("application/vnd.ms-powerpoint")) return PPT;
        if (n.endsWith(".csv") || n.endsWith(".tsv") || m.equals("text/csv") || m.equals("text/comma-separated-values") || m.equals("text/tab-separated-values")) return CSV;
        if (n.endsWith(".txt") || n.endsWith(".log") || n.endsWith(".ini") || n.endsWith(".json") || n.endsWith(".xml") || n.endsWith(".srt") || m.equals("text/plain")) return TXT;
        return NONE;
    }

    public static boolean isEditable(int kind) {
        return kind == DOCX || kind == XLSX || kind == CSV || kind == TXT || kind == DOC || kind == XLS;
    }

    /** Old binary files are saved in the new format: .doc edits become a .docx, .xls edits an .xlsx. */
    public static int savedKind(int kind) {
        return kind == DOC ? DOCX : kind == XLS ? XLSX : kind;
    }

    public static String savedName(String name, int kind) {
        if (kind != DOC && kind != XLS) return name;
        String base = name == null ? "document" : name;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        return base + (kind == DOC ? ".docx" : ".xlsx");
    }

    /** The name says .doc but the bytes are a .docx (or the other way round): trust the bytes. */
    public static int sniff(File file, int kind) {
        if (kind == NONE || kind == CSV || kind == TXT) return kind;
        byte[] head = new byte[8];
        try (FileInputStream in = new FileInputStream(file)) {
            if (in.read(head) < 8) return kind;
        } catch (IOException e) {
            return kind;
        }
        boolean zip = head[0] == 'P' && head[1] == 'K';
        boolean ole = UMessageOfficeLegacy.isCompoundFile(head);
        if (zip) {
            if (kind == DOC) return DOCX;
            if (kind == XLS) return XLSX;
            if (kind == PPT) return PPTX;
        } else if (ole) {
            if (kind == DOCX) return DOC;
            if (kind == XLSX) return XLS;
            if (kind == PPTX) return PPT;
        }
        return kind;
    }

    public static String mimeOf(int kind) {
        switch (kind) {
            case DOCX: return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case XLSX: return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case PPTX: return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case CSV: return "text/csv";
            case DOC: return "application/msword";
            case XLS: return "application/vnd.ms-excel";
            case PPT: return "application/vnd.ms-powerpoint";
            default: return "text/plain";
        }
    }

    /** Thrown for files that open but use something we can't show (password, old binary format inside). */
    public static class UnsupportedException extends Exception {
        public UnsupportedException(String message) {
            super(message);
        }
    }

    // ===================================================================== HTML

    /** The whole page; {@code background} is the colour around the paper. */
    public static String toHtml(File file, int kind, int background) throws Exception {
        StringBuilder body = new StringBuilder();
        String mode;
        switch (kind) {
            case DOCX:
                mode = "doc";
                try (ZipFile zip = new ZipFile(file)) {
                    new DocxRenderer(zip).render(body);
                }
                break;
            case XLSX:
                mode = "sheet";
                try (ZipFile zip = new ZipFile(file)) {
                    new XlsxRenderer(zip).render(body);
                }
                break;
            case PPTX:
                mode = "slides";
                try (ZipFile zip = new ZipFile(file)) {
                    new PptxRenderer(zip).render(body);
                }
                break;
            case CSV:
                mode = "sheet";
                renderCsv(file, body);
                break;
            case DOC:
                mode = "doc";
                UMessageOfficeLegacy.renderDoc(file, body);
                break;
            case XLS:
                mode = "sheet";
                UMessageOfficeLegacy.renderXls(file, body);
                break;
            case PPT:
                mode = "slides";
                UMessageOfficeLegacy.renderPpt(file, body);
                break;
            case TXT:
                mode = "text";
                body.append("<pre class=\"txt\" data-e=\"t\">").append(esc(readText(file).text)).append("</pre>");
                break;
            default:
                throw new UnsupportedException("kind");
        }
        return page(mode, body, background);
    }

    private static String page(String mode, CharSequence body, int background) {
        String bg = String.format(Locale.ROOT, "#%06x", background & 0xffffff);
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,minimum-scale=0.25,maximum-scale=6,user-scalable=yes\">"
                + "<style>" + CSS.replace("$BG", bg) + "</style></head><body class=\"m-" + mode + "\">"
                + body
                + "<script>" + JS + "</script></body></html>";
    }

    private static final String CSS =
            "html,body{margin:0;padding:0;background:$BG;-webkit-text-size-adjust:none}"
            + "body{font-family:Calibri,Carlito,Roboto,'Noto Sans',sans-serif;color:#111;padding:8px 8px 28px}"
            + ".paper{background:#fff;margin:0 auto;max-width:820px;padding:28px 26px;border-radius:12px;box-shadow:0 1px 3px rgba(0,0,0,.18);font-size:11pt;line-height:1.35;overflow-wrap:break-word}"
            + ".paper p{margin:0 0 6pt;min-height:1em;white-space:pre-wrap}"
            + ".paper h1,.paper h2,.paper h3,.paper h4,.paper h5,.paper h6{margin:10pt 0 6pt;white-space:pre-wrap}"
            + ".paper img{max-width:100%;height:auto;vertical-align:middle}"
            + ".paper table{border-collapse:collapse;margin:6pt 0;max-width:100%}"
            + ".paper td{border:1px solid #9a9a9a;padding:3px 6px;vertical-align:top}"
            + ".paper td p{margin:0}"
            + ".tab{white-space:pre}"
            + "[data-n]::before{content:attr(data-n)}"
            + ".lnk{color:#1a5fb4;text-decoration:underline}"
            + "[contenteditable=true]{outline:none;border-radius:3px}"
            + "body.editing [data-e]{box-shadow:inset 0 0 0 1px rgba(61,139,242,.25)}"
            + "body.editing [data-e]:focus{box-shadow:inset 0 0 0 2px #3d8bf2;background:rgba(61,139,242,.06)}"
            + "[data-e].changed{background:rgba(255,214,10,.22)}"
            // sheets
            + ".tabs{position:sticky;top:0;z-index:5;display:flex;gap:6px;overflow-x:auto;padding:2px 0 8px;background:$BG}"
            + ".tabs b{flex:none;font:600 13px Roboto,sans-serif;padding:7px 14px;border-radius:16px;background:rgba(127,127,127,.18);color:inherit}"
            + ".tabs b.on{background:#3d8bf2;color:#fff}"
            + ".sheet{display:none;overflow:auto;background:#fff;border-radius:10px;box-shadow:0 1px 3px rgba(0,0,0,.18)}"
            + ".sheet.on{display:block}"
            + ".grid{border-collapse:separate;border-spacing:0;font-size:13px;table-layout:fixed}"
            + ".grid td{border-right:1px solid #e1e1e1;border-bottom:1px solid #e1e1e1;padding:3px 5px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;height:20px;max-width:600px}"
            + ".grid td.w{white-space:pre-wrap}"
            + ".grid th{background:#f3f3f3;color:#666;font:500 11px Roboto,sans-serif;border-right:1px solid #d6d6d6;border-bottom:1px solid #d6d6d6;padding:2px 4px;position:sticky;z-index:2}"
            + ".grid thead th{top:0;z-index:3}"
            + ".grid tbody th{left:0;min-width:32px;text-align:center}"
            + ".grid thead th.c{left:0;z-index:4}"
            + ".grid td.n{text-align:right}"
            + ".grid td.f{color:#0b6b3a}"
            + ".note{font:13px Roboto,sans-serif;color:#888;padding:10px 4px}"
            // slides
            + ".slide{position:relative;background:#fff;margin:0 auto 14px;max-width:960px;border-radius:8px;box-shadow:0 1px 3px rgba(0,0,0,.2);overflow:hidden}"
            + ".slide .sp{position:absolute;overflow:hidden;box-sizing:border-box;padding:0.4%}"
            + ".slide .sp p{margin:0;white-space:pre-wrap;line-height:1.15}"
            + ".slide img{position:absolute;object-fit:contain}"
            + ".pslide{background:#fff;margin:0 auto 14px;max-width:960px;border-radius:8px;box-shadow:0 1px 3px rgba(0,0,0,.2);padding:5% 6%;box-sizing:border-box;overflow-wrap:break-word}"
            + ".pslide h2{margin:0 0 12px;font-size:22px}"
            + ".pslide p{margin:0 0 6px;font-size:15px;white-space:pre-wrap}"
            + ".num{font:12px Roboto,sans-serif;color:#888;text-align:center;margin:-8px 0 12px}"
            // text
            + ".txt{background:#fff;margin:0;padding:16px;border-radius:10px;box-shadow:0 1px 3px rgba(0,0,0,.18);font:13px/1.45 'Roboto Mono',monospace;white-space:pre-wrap;overflow-wrap:anywhere;min-height:60vh}";

    private static final String JS =
            "var UM={edit:false,dirty:{}};"
            + "UM.setEdit=function(on){UM.edit=on;document.body.classList.toggle('editing',on);"
            + "document.querySelectorAll('[data-e]').forEach(function(e){e.contentEditable=on?'true':'false';});};"
            + "UM.count=function(){return Object.keys(UM.dirty).length;};"
            + "UM.collect=function(){var o=[];for(var k in UM.dirty){o.push({k:k,v:UM.dirty[k].innerText});}return JSON.stringify(o);};"
            + "document.addEventListener('input',function(ev){var e=ev.target.closest?ev.target.closest('[data-e]'):null;"
            + "if(e){UM.dirty[e.getAttribute('data-e')]=e;e.classList.add('changed');if(window.UMHost)UMHost.changed();}},true);"
            + "document.addEventListener('keydown',function(ev){if(!UM.edit||ev.key!=='Enter')return;var e=ev.target.closest?ev.target.closest('[data-e]'):null;if(!e)return;"
            + "if(e.tagName==='TD'){ev.preventDefault();e.blur();}"
            + "else if(e.tagName!=='PRE'){ev.preventDefault();document.execCommand('insertLineBreak');}},true);"
            + "document.addEventListener('paste',function(ev){if(!UM.edit)return;ev.preventDefault();"
            + "var t=(ev.clipboardData||window.clipboardData).getData('text/plain');document.execCommand('insertText',false,t);},true);"
            + "UM.sheet=function(i){document.querySelectorAll('.sheet').forEach(function(s,j){s.classList.toggle('on',i===j);});"
            + "document.querySelectorAll('.tabs b').forEach(function(s,j){s.classList.toggle('on',i===j);});};"
            + "UM.fit=function(){document.querySelectorAll('.slide').forEach(function(s){"
            + "s.style.height=(s.clientWidth*s.getAttribute('data-r'))+'px';s.style.setProperty('--k',s.clientWidth/s.getAttribute('data-w'));});};"
            + "window.addEventListener('resize',UM.fit);UM.fit();";

    // ===================================================================== small XML tree

    /** Element with its children; attribute keys are local names (r:embed is "embed"). */
    static final class X {
        final String ns, name;
        final HashMap<String, String> a = new HashMap<>();
        final ArrayList<Object> k = new ArrayList<>();
        int index = -1;

        X(String ns, String name) {
            this.ns = ns;
            this.name = name;
        }

        String attr(String n) {
            return a.get(n);
        }

        X child(String n) {
            for (Object o : k) {
                if (o instanceof X && ((X) o).name.equals(n)) return (X) o;
            }
            return null;
        }

        ArrayList<X> children(String n) {
            ArrayList<X> out = new ArrayList<>();
            for (Object o : k) {
                if (o instanceof X && (n == null || ((X) o).name.equals(n))) out.add((X) o);
            }
            return out;
        }

        X find(String n) {
            for (Object o : k) {
                if (o instanceof X) {
                    X x = (X) o;
                    if (x.name.equals(n)) return x;
                    X f = x.find(n);
                    if (f != null) return f;
                }
            }
            return null;
        }

        void findAll(String n, ArrayList<X> out) {
            for (Object o : k) {
                if (o instanceof X) {
                    X x = (X) o;
                    if (x.name.equals(n)) out.add(x);
                    x.findAll(n, out);
                }
            }
        }

        String text() {
            StringBuilder sb = new StringBuilder();
            for (Object o : k) {
                if (o instanceof String) sb.append((String) o);
                else sb.append(((X) o).text());
            }
            return sb.toString();
        }

        String childVal(String n) {
            X c = child(n);
            return c == null ? null : c.attr("val");
        }
    }

    /** Builds the tree; every W:p gets its document-order number, the same count the saver uses. */
    static X parse(byte[] data) throws Exception {
        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        parser.setInput(new ByteArrayInputStream(data), null);
        X root = new X("", "#root");
        ArrayList<X> stack = new ArrayList<>();
        stack.add(root);
        int paragraphs = 0;
        int event = parser.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                X x = new X(parser.getNamespace(), parser.getName());
                for (int i = 0; i < parser.getAttributeCount(); i++) {
                    x.a.put(parser.getAttributeName(i), parser.getAttributeValue(i));
                }
                if (W.equals(x.ns) && "p".equals(x.name)) x.index = paragraphs++;
                stack.get(stack.size() - 1).k.add(x);
                stack.add(x);
            } else if (event == XmlPullParser.END_TAG) {
                stack.remove(stack.size() - 1);
            } else if (event == XmlPullParser.TEXT) {
                X top = stack.get(stack.size() - 1);
                String t = parser.getText();
                if (t != null && (!t.trim().isEmpty() || "t".equals(top.name))) top.k.add(t);
            }
            event = parser.next();
        }
        return root;
    }

    static byte[] read(ZipFile zip, String path) throws IOException {
        if (path == null) return null;
        ZipEntry entry = zip.getEntry(path);
        if (entry == null) return null;
        try (InputStream in = zip.getInputStream(entry)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            long total = 0;
            int n;
            while ((n = in.read(buffer)) > 0) {
                total += n;
                if (total > MAX_PART) throw new IOException("part too large");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    static X xml(ZipFile zip, String path) throws Exception {
        byte[] data = read(zip, path);
        return data == null ? null : parse(data);
    }

    /** Relationship id -> package path, for the part at {@code partPath}. */
    static HashMap<String, String> rels(ZipFile zip, String partPath) throws Exception {
        HashMap<String, String> map = new HashMap<>();
        int slash = partPath.lastIndexOf('/');
        String dir = slash < 0 ? "" : partPath.substring(0, slash + 1);
        String relsPath = dir + "_rels/" + partPath.substring(slash + 1) + ".rels";
        X root = xml(zip, relsPath);
        if (root == null) return map;
        ArrayList<X> list = new ArrayList<>();
        root.findAll("Relationship", list);
        for (X r : list) {
            if ("External".equals(r.attr("TargetMode"))) continue;
            String target = r.attr("Target");
            if (target == null) continue;
            map.put(r.attr("Id"), resolve(dir, target));
        }
        return map;
    }

    static String resolve(String dir, String target) {
        String path = target.startsWith("/") ? target.substring(1) : dir + target;
        ArrayList<String> parts = new ArrayList<>();
        for (String s : path.split("/")) {
            if (s.isEmpty() || s.equals(".")) continue;
            if (s.equals("..")) {
                if (!parts.isEmpty()) parts.remove(parts.size() - 1);
            } else {
                parts.add(s);
            }
        }
        return TextUtils.join("/", parts);
    }

    /** Images are inlined so the page needs no file or network access. */
    static final class Images {
        final ZipFile zip;
        long total;

        Images(ZipFile zip) {
            this.zip = zip;
        }

        String dataUri(String path) {
            if (path == null) return null;
            try {
                ZipEntry entry = zip.getEntry(path);
                if (entry == null || entry.getSize() > MAX_IMAGE || total + Math.max(0, entry.getSize()) > MAX_IMAGES_TOTAL) return null;
                String ext = path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
                String mime;
                switch (ext) {
                    case "png": mime = "image/png"; break;
                    case "jpg": case "jpeg": mime = "image/jpeg"; break;
                    case "gif": mime = "image/gif"; break;
                    case "bmp": mime = "image/bmp"; break;
                    case "webp": mime = "image/webp"; break;
                    case "svg": mime = "image/svg+xml"; break;
                    default: return null; // emf/wmf/tiff: WebView can't draw them
                }
                byte[] data = read(zip, path);
                if (data == null || data.length > MAX_IMAGE) return null;
                total += data.length;
                return "data:" + mime + ";base64," + Base64.encodeToString(data, Base64.NO_WRAP);
            } catch (Throwable e) {
                FileLog.e(e);
                return null;
            }
        }
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    static String color(String hex) {
        if (hex == null) return null;
        if (hex.length() == 8) hex = hex.substring(2);
        if (hex.length() != 6 || !hex.matches("[0-9A-Fa-f]{6}")) return null;
        return "#" + hex;
    }

    // ===================================================================== Word

    static final class DocxRenderer {
        final ZipFile zip;
        final Images images;
        HashMap<String, String> rels;
        final HashMap<String, String> styleNames = new HashMap<>();
        final HashMap<String, String> numToAbstract = new HashMap<>();
        final HashMap<String, String[]> levelFormat = new HashMap<>(); // abstractId:ilvl -> {numFmt, lvlText}
        final HashMap<String, int[]> counters = new HashMap<>();

        DocxRenderer(ZipFile zip) {
            this.zip = zip;
            this.images = new Images(zip);
        }

        void render(StringBuilder out) throws Exception {
            String main = "word/document.xml";
            X types = xml(zip, "[Content_Types].xml");
            if (types != null) {
                for (X o : types.find("Types") != null ? types.find("Types").children("Override") : new ArrayList<X>()) {
                    String ct = o.attr("ContentType");
                    if (ct != null && ct.contains("wordprocessingml.document.main")) main = o.attr("PartName").substring(1);
                }
            }
            X doc = xml(zip, main);
            if (doc == null) throw new UnsupportedException("no document");
            rels = rels(zip, main);
            loadStyles();
            loadNumbering();
            X body = doc.find("body");
            out.append("<div class=\"paper\">");
            if (body != null) renderBlocks(body, out, true);
            out.append("</div>");
        }

        void loadStyles() {
            try {
                X root = xml(zip, "word/styles.xml");
                if (root == null) return;
                ArrayList<X> list = new ArrayList<>();
                root.findAll("style", list);
                for (X s : list) {
                    String id = s.attr("styleId");
                    String name = s.childVal("name");
                    if (id != null && name != null) styleNames.put(id, name.toLowerCase(Locale.ROOT));
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }

        void loadNumbering() {
            try {
                X root = xml(zip, "word/numbering.xml");
                if (root == null) return;
                X numbering = root.find("numbering");
                if (numbering == null) return;
                for (X abs : numbering.children("abstractNum")) {
                    String id = abs.attr("abstractNumId");
                    for (X lvl : abs.children("lvl")) {
                        levelFormat.put(id + ":" + lvl.attr("ilvl"), new String[]{lvl.childVal("numFmt"), lvl.childVal("lvlText")});
                    }
                }
                for (X num : numbering.children("num")) {
                    numToAbstract.put(num.attr("numId"), num.childVal("abstractNumId"));
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }

        void renderBlocks(X parent, StringBuilder out, boolean editable) {
            for (Object o : parent.k) {
                if (!(o instanceof X)) continue;
                X x = (X) o;
                switch (x.name) {
                    case "p": renderParagraph(x, out, editable); break;
                    case "tbl": renderTable(x, out); break;
                    case "sdt": {
                        X content = x.child("sdtContent");
                        if (content != null) renderBlocks(content, out, editable);
                        break;
                    }
                    case "customXml": case "ins": renderBlocks(x, out, editable); break;
                    default: break;
                }
            }
        }

        void renderParagraph(X p, StringBuilder out, boolean editable) {
            X pPr = p.child("pPr");
            String tag = "p";
            StringBuilder style = new StringBuilder();
            String prefix = null;
            if (pPr != null) {
                String styleId = pPr.childVal("pStyle");
                String name = styleId == null ? null : styleNames.get(styleId);
                if (name == null && styleId != null) name = styleId.toLowerCase(Locale.ROOT);
                if (name != null) {
                    if (name.equals("title")) tag = "h1";
                    else if (name.startsWith("heading ") || name.startsWith("heading")) {
                        char d = name.charAt(name.length() - 1);
                        if (d >= '1' && d <= '6') tag = "h" + d;
                    }
                }
                String jc = pPr.childVal("jc");
                if ("center".equals(jc)) style.append("text-align:center;");
                else if ("right".equals(jc) || "end".equals(jc)) style.append("text-align:right;");
                else if ("both".equals(jc) || "distribute".equals(jc)) style.append("text-align:justify;");
                X ind = pPr.child("ind");
                if (ind != null) {
                    String left = ind.attr("left") != null ? ind.attr("left") : ind.attr("start");
                    int twips = parseInt(left, 0);
                    if (twips > 0) style.append("margin-left:").append(Math.min(twips / 20, 200)).append("pt;");
                    int first = parseInt(ind.attr("firstLine"), 0);
                    if (first > 0) style.append("text-indent:").append(Math.min(first / 20, 100)).append("pt;");
                }
                X numPr = pPr.child("numPr");
                if (numPr != null) prefix = listPrefix(numPr.childVal("numId"), numPr.childVal("ilvl"));
            }
            // A paragraph holding other paragraphs (text boxes) can't be rewritten as one run of text.
            ArrayList<X> nested = new ArrayList<>();
            p.findAll("p", nested);
            out.append('<').append(tag);
            if (style.length() > 0) out.append(" style=\"").append(style).append('"');
            if (editable && nested.isEmpty() && p.index >= 0) out.append(" data-e=\"p:").append(p.index).append('"');
            // drawn by CSS, so the number is not part of the text that gets saved
            if (prefix != null) out.append(" data-n=\"").append(esc(prefix)).append('"');
            out.append('>');
            int before = out.length();
            renderInline(p, out);
            if (out.length() == before) out.append("<br>");
            out.append("</").append(tag).append('>');
            for (X inner : nested) {
                // text box content shown after its anchor paragraph, read only
                if (inner.find("p") == null) renderParagraph(inner, out, false);
            }
        }

        String listPrefix(String numId, String ilvl) {
            if (numId == null || "0".equals(numId)) return null;
            int level = parseInt(ilvl, 0);
            String abs = numToAbstract.get(numId);
            String[] fmt = abs == null ? null : levelFormat.get(abs + ":" + level);
            int[] c = counters.get(numId);
            if (c == null) counters.put(numId, c = new int[9]);
            if (level < 0 || level > 8) level = 0;
            c[level]++;
            for (int i = level + 1; i < 9; i++) c[i] = 0;
            if (fmt == null || fmt[0] == null || "bullet".equals(fmt[0])) return "• ";
            if ("none".equals(fmt[0])) return null;
            String text = fmt[1] == null ? "%" + (level + 1) + "." : fmt[1];
            for (int i = 0; i <= level; i++) {
                String[] f = abs == null ? null : levelFormat.get(abs + ":" + i);
                text = text.replace("%" + (i + 1), numberText(Math.max(1, c[i]), f == null ? "decimal" : f[0]));
            }
            return text + " ";
        }

        void renderInline(X parent, StringBuilder out) {
            for (Object o : parent.k) {
                if (!(o instanceof X)) continue;
                X x = (X) o;
                switch (x.name) {
                    case "r": renderRun(x, out); break;
                    case "hyperlink":
                        out.append("<span class=\"lnk\">");
                        renderInline(x, out);
                        out.append("</span>");
                        break;
                    case "ins": case "smartTag": case "fldSimple": case "customXml": case "bdo": case "dir":
                        renderInline(x, out);
                        break;
                    case "sdt": {
                        X content = x.child("sdtContent");
                        if (content != null) renderInline(content, out);
                        break;
                    }
                    case "oMath": case "oMathPara":
                        out.append("<i>").append(esc(x.text())).append("</i>");
                        break;
                    default: break; // del, bookmarks, proofErr, pPr
                }
            }
        }

        void renderRun(X r, StringBuilder out) {
            X rPr = r.child("rPr");
            StringBuilder style = new StringBuilder();
            boolean sup = false, sub = false;
            if (rPr != null) {
                if (on(rPr.child("b"))) style.append("font-weight:bold;");
                if (on(rPr.child("i"))) style.append("font-style:italic;");
                X u = rPr.child("u");
                boolean underline = u != null && !"none".equals(u.attr("val"));
                boolean strike = on(rPr.child("strike")) || on(rPr.child("dstrike"));
                if (underline || strike) style.append("text-decoration:").append(underline ? "underline " : "").append(strike ? "line-through" : "").append(';');
                String c = color(rPr.childVal("color"));
                if (c != null) style.append("color:").append(c).append(';');
                int sz = parseInt(rPr.childVal("sz"), 0);
                if (sz > 0) style.append("font-size:").append(sz / 2f).append("pt;");
                String hl = highlight(rPr.childVal("highlight"));
                if (hl == null) {
                    X shd = rPr.child("shd");
                    if (shd != null) hl = color(shd.attr("fill"));
                }
                if (hl != null) style.append("background:").append(hl).append(';');
                String va = rPr.childVal("vertAlign");
                sup = "superscript".equals(va);
                sub = "subscript".equals(va);
                if (on(rPr.child("caps"))) style.append("text-transform:uppercase;");
                if (on(rPr.child("vanish"))) return;
            }
            StringBuilder inner = new StringBuilder();
            for (Object o : r.k) {
                if (!(o instanceof X)) continue;
                X x = (X) o;
                switch (x.name) {
                    case "t": inner.append(esc(x.text())); break;
                    case "tab": inner.append("<span class=\"tab\">\t</span>"); break;
                    case "br": case "cr": inner.append("<br>"); break;
                    case "noBreakHyphen": inner.append('-'); break;
                    case "sym": {
                        int code = parseHex(x.attr("char"));
                        if (code > 0xF000) code -= 0xF000;
                        if (code > 0) inner.append(esc(String.valueOf((char) code)));
                        break;
                    }
                    case "drawing": case "pict": case "object": case "AlternateContent":
                        image(x, inner);
                        break;
                    default: break;
                }
            }
            if (inner.length() == 0) return;
            if (sup) out.append("<sup>");
            if (sub) out.append("<sub>");
            if (style.length() > 0) out.append("<span style=\"").append(style).append("\">");
            out.append(inner);
            if (style.length() > 0) out.append("</span>");
            if (sub) out.append("</sub>");
            if (sup) out.append("</sup>");
        }

        void image(X x, StringBuilder out) {
            // the first picture is enough: AlternateContent repeats it as a fallback
            X blip = x.find("blip");
            String id = blip != null ? blip.attr("embed") : null;
            if (id == null) {
                X data = x.find("imagedata");
                if (data != null) id = data.attr("id");
            }
            String uri = id == null ? null : images.dataUri(rels.get(id));
            if (uri == null) return;
            String style = "";
            X extent = x.find("extent");
            if (extent != null) {
                long cx = parseLong(extent.attr("cx"));
                if (cx > 0) style = " style=\"width:" + Math.round(cx / 12700f) + "pt\"";
            }
            out.append("<img contenteditable=\"false\"").append(style).append(" src=\"").append(uri).append("\">");
        }

        void renderTable(X tbl, StringBuilder out) {
            ArrayList<X> rows = tbl.children("tr");
            // [row][cell] with rowspans worked out from vMerge
            ArrayList<ArrayList<int[]>> spans = new ArrayList<>();
            HashMap<Integer, int[]> openMerge = new HashMap<>();
            ArrayList<ArrayList<X>> cells = new ArrayList<>();
            for (X tr : rows) {
                ArrayList<X> rowCells = new ArrayList<>();
                ArrayList<int[]> rowSpans = new ArrayList<>();
                int col = 0;
                for (X tc : tcs(tr)) {
                    X tcPr = tc.child("tcPr");
                    int colspan = Math.max(1, tcPr == null ? 1 : parseInt(tcPr.childVal("gridSpan"), 1));
                    X vMerge = tcPr == null ? null : tcPr.child("vMerge");
                    int[] span = new int[]{colspan, 1, 0};
                    if (vMerge != null && !"restart".equals(vMerge.attr("val"))) {
                        int[] start = openMerge.get(col);
                        if (start != null) {
                            start[1]++;
                            span[2] = 1; // hidden
                        }
                    } else if (vMerge != null) {
                        openMerge.put(col, span);
                    } else {
                        openMerge.remove(col);
                    }
                    rowCells.add(tc);
                    rowSpans.add(span);
                    col += colspan;
                }
                cells.add(rowCells);
                spans.add(rowSpans);
            }
            out.append("<table>");
            for (int r = 0; r < cells.size(); r++) {
                out.append("<tr>");
                for (int c = 0; c < cells.get(r).size(); c++) {
                    int[] span = spans.get(r).get(c);
                    if (span[2] == 1) continue;
                    X tc = cells.get(r).get(c);
                    out.append("<td");
                    if (span[0] > 1) out.append(" colspan=\"").append(span[0]).append('"');
                    if (span[1] > 1) out.append(" rowspan=\"").append(span[1]).append('"');
                    X tcPr = tc.child("tcPr");
                    X shd = tcPr == null ? null : tcPr.child("shd");
                    String fill = shd == null ? null : color(shd.attr("fill"));
                    if (fill != null) out.append(" style=\"background:").append(fill).append('"');
                    out.append('>');
                    renderBlocks(tc, out, true);
                    out.append("</td>");
                }
                out.append("</tr>");
            }
            out.append("</table>");
        }

        ArrayList<X> tcs(X tr) {
            ArrayList<X> out = new ArrayList<>();
            for (Object o : tr.k) {
                if (!(o instanceof X)) continue;
                X x = (X) o;
                if (x.name.equals("tc")) out.add(x);
                else if (x.name.equals("sdt") && x.child("sdtContent") != null) out.addAll(x.child("sdtContent").children("tc"));
                else if (x.name.equals("customXml")) out.addAll(x.children("tc"));
            }
            return out;
        }
    }

    static boolean on(X flag) {
        if (flag == null) return false;
        String v = flag.attr("val");
        return v == null || !(v.equals("0") || v.equals("false") || v.equals("off") || v.equals("none"));
    }

    static String highlight(String name) {
        if (name == null || "none".equals(name)) return null;
        switch (name) {
            case "yellow": return "#ffff00";
            case "green": return "#00ff00";
            case "cyan": return "#00ffff";
            case "magenta": return "#ff00ff";
            case "blue": return "#0000ff";
            case "red": return "#ff0000";
            case "darkBlue": return "#000080";
            case "darkCyan": return "#008080";
            case "darkGreen": return "#008000";
            case "darkMagenta": return "#800080";
            case "darkRed": return "#800000";
            case "darkYellow": return "#808000";
            case "darkGray": return "#808080";
            case "lightGray": return "#c0c0c0";
            case "black": return "#000000";
            default: return null;
        }
    }

    static String numberText(int n, String format) {
        if (format == null) format = "decimal";
        switch (format) {
            case "lowerLetter": return letters(n).toLowerCase(Locale.ROOT);
            case "upperLetter": return letters(n);
            case "lowerRoman": return roman(n).toLowerCase(Locale.ROOT);
            case "upperRoman": return roman(n);
            case "decimalZero": return n < 10 ? "0" + n : String.valueOf(n);
            default: return String.valueOf(n);
        }
    }

    static String letters(int n) {
        StringBuilder sb = new StringBuilder();
        while (n > 0) {
            n--;
            sb.insert(0, (char) ('A' + n % 26));
            n /= 26;
        }
        return sb.toString();
    }

    static String roman(int n) {
        int[] v = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] s = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length && n > 0; i++) {
            while (n >= v[i]) {
                sb.append(s[i]);
                n -= v[i];
            }
        }
        return sb.toString();
    }

    static int parseInt(String s, int def) {
        if (s == null) return def;
        try {
            return (int) Double.parseDouble(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    static long parseLong(String s) {
        if (s == null) return 0;
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    static int parseHex(String s) {
        if (s == null) return 0;
        try {
            return Integer.parseInt(s.trim(), 16);
        } catch (Exception e) {
            return 0;
        }
    }

    // ===================================================================== Excel

    static final class XlsxRenderer {
        final ZipFile zip;
        final ArrayList<String> shared = new ArrayList<>();
        final ArrayList<int[]> xfs = new ArrayList<>(); // numFmtId, fontId, fillId, align(0 none 1 left 2 center 3 right), wrap
        final HashMap<Integer, String> formats = new HashMap<>();
        final ArrayList<String> fontCss = new ArrayList<>();
        final ArrayList<String> fillCss = new ArrayList<>();

        XlsxRenderer(ZipFile zip) {
            this.zip = zip;
        }

        void render(StringBuilder out) throws Exception {
            X workbook = xml(zip, "xl/workbook.xml");
            if (workbook == null) throw new UnsupportedException("no workbook");
            HashMap<String, String> rels = rels(zip, "xl/workbook.xml");
            loadShared();
            loadStyles();
            X sheetsNode = workbook.find("sheets");
            ArrayList<X> sheets = sheetsNode == null ? new ArrayList<X>() : sheetsNode.children("sheet");
            ArrayList<String> names = new ArrayList<>();
            StringBuilder bodies = new StringBuilder();
            int index = 0;
            for (X sheet : sheets) {
                String path = rels.get(sheet.attr("id"));
                X root = path == null ? null : xml(zip, path);
                if (root == null || root.find("worksheet") == null) {
                    index++;
                    continue; // chart sheets and the like
                }
                boolean hidden = sheet.attr("state") != null && !"visible".equals(sheet.attr("state"));
                if (!hidden) {
                    names.add(sheet.attr("name"));
                    bodies.append("<div class=\"sheet").append(names.size() == 1 ? " on" : "").append("\">");
                    renderSheet(root.find("worksheet"), index, bodies);
                    bodies.append("</div>");
                }
                index++;
            }
            if (names.size() > 1) {
                out.append("<div class=\"tabs\">");
                for (int i = 0; i < names.size(); i++) {
                    out.append("<b").append(i == 0 ? " class=\"on\"" : "").append(" onclick=\"UM.sheet(").append(i).append(")\">").append(esc(names.get(i))).append("</b>");
                }
                out.append("</div>");
            }
            out.append(bodies);
        }

        void loadShared() {
            try {
                X root = xml(zip, "xl/sharedStrings.xml");
                if (root == null) return;
                X sst = root.find("sst");
                if (sst == null) return;
                for (X si : sst.children("si")) {
                    StringBuilder sb = new StringBuilder();
                    for (Object o : si.k) {
                        if (!(o instanceof X)) continue;
                        X x = (X) o;
                        if (x.name.equals("t")) sb.append(x.text());
                        else if (x.name.equals("r") && x.child("t") != null) sb.append(x.child("t").text());
                    }
                    shared.add(sb.toString());
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }

        void loadStyles() {
            try {
                X root = xml(zip, "xl/styles.xml");
                if (root == null) return;
                X numFmts = root.find("numFmts");
                if (numFmts != null) {
                    for (X f : numFmts.children("numFmt")) formats.put(parseInt(f.attr("numFmtId"), -1), f.attr("formatCode"));
                }
                X fonts = root.find("fonts");
                if (fonts != null) {
                    for (X f : fonts.children("font")) {
                        StringBuilder css = new StringBuilder();
                        if (on(f.child("b"))) css.append("font-weight:bold;");
                        if (on(f.child("i"))) css.append("font-style:italic;");
                        X u = f.child("u");
                        if (u != null && !"none".equals(u.attr("val"))) css.append("text-decoration:underline;");
                        X c = f.child("color");
                        String rgb = c == null ? null : color(c.attr("rgb"));
                        if (rgb != null && !rgb.equalsIgnoreCase("#000000")) css.append("color:").append(rgb).append(';');
                        fontCss.add(css.toString());
                    }
                }
                X fills = root.find("fills");
                if (fills != null) {
                    for (X f : fills.children("fill")) {
                        X pattern = f.child("patternFill");
                        String css = "";
                        if (pattern != null && "solid".equals(pattern.attr("patternType"))) {
                            X fg = pattern.child("fgColor");
                            String rgb = fg == null ? null : color(fg.attr("rgb"));
                            if (rgb != null) css = "background:" + rgb + ";";
                        }
                        fillCss.add(css);
                    }
                }
                X cellXfs = root.find("cellXfs");
                if (cellXfs != null) {
                    for (X xf : cellXfs.children("xf")) {
                        X al = xf.child("alignment");
                        int align = 0;
                        boolean wrap = false;
                        if (al != null) {
                            String h = al.attr("horizontal");
                            if ("left".equals(h)) align = 1;
                            else if ("center".equals(h) || "centerContinuous".equals(h)) align = 2;
                            else if ("right".equals(h)) align = 3;
                            wrap = "1".equals(al.attr("wrapText")) || "true".equals(al.attr("wrapText"));
                        }
                        xfs.add(new int[]{parseInt(xf.attr("numFmtId"), 0), parseInt(xf.attr("fontId"), 0), parseInt(xf.attr("fillId"), 0), align, wrap ? 1 : 0});
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }

        void renderSheet(X ws, int sheetIndex, StringBuilder out) {
            HashMap<Long, X> cells = new HashMap<>();
            HashMap<Integer, Float> rowHeights = new HashMap<>();
            int maxRow = 0, maxCol = 0;
            boolean truncated = false;
            X sheetData = ws.child("sheetData");
            if (sheetData != null) {
                int rowCounter = 0;
                for (X row : sheetData.children("row")) {
                    int r = parseInt(row.attr("r"), rowCounter + 1);
                    rowCounter = r;
                    if (row.attr("ht") != null && "1".equals(row.attr("customHeight"))) rowHeights.put(r, (float) parseInt(row.attr("ht"), 15));
                    int colCounter = 0;
                    for (X c : row.children("c")) {
                        int col;
                        String ref = c.attr("r");
                        col = ref != null ? colOf(ref) : colCounter + 1;
                        colCounter = col;
                        if (r > MAX_ROWS || col > MAX_COLS) {
                            truncated = true;
                            continue;
                        }
                        if (!hasValue(c) && c.attr("s") == null) continue;
                        cells.put(key(r, col), c);
                        if (hasValue(c)) {
                            maxRow = Math.max(maxRow, r);
                            maxCol = Math.max(maxCol, col);
                        }
                    }
                }
            }
            HashMap<Long, int[]> merges = new HashMap<>();
            HashSet<Long> covered = new HashSet<>();
            X mergeCells = ws.child("mergeCells");
            if (mergeCells != null) {
                for (X m : mergeCells.children("mergeCell")) {
                    String ref = m.attr("ref");
                    if (ref == null || !ref.contains(":")) continue;
                    String[] ab = ref.split(":");
                    int r1 = rowOf(ab[0]), c1 = colOf(ab[0]), r2 = rowOf(ab[1]), c2 = colOf(ab[1]);
                    if (r1 <= 0 || c1 <= 0 || r2 < r1 || c2 < c1) continue;
                    merges.put(key(r1, c1), new int[]{r2 - r1 + 1, c2 - c1 + 1});
                    for (int r = r1; r <= r2; r++) {
                        for (int c = c1; c <= c2; c++) {
                            if (r != r1 || c != c1) covered.add(key(r, c));
                        }
                    }
                    maxRow = Math.max(maxRow, Math.min(r2, MAX_ROWS));
                    maxCol = Math.max(maxCol, Math.min(c2, MAX_COLS));
                }
            }
            // a little room around the data, so empty cells next to it can be filled in
            maxRow = Math.min(MAX_ROWS, Math.max(maxRow + 5, 20));
            maxCol = Math.min(MAX_COLS, Math.max(maxCol + 2, 6));
            float[] widths = new float[maxCol + 1];
            for (int i = 1; i <= maxCol; i++) widths[i] = 8.43f;
            X cols = ws.child("cols");
            if (cols != null) {
                for (X col : cols.children("col")) {
                    int min = parseInt(col.attr("min"), 1), max = Math.min(parseInt(col.attr("max"), min), maxCol);
                    float w;
                    try {
                        w = Float.parseFloat(col.attr("width"));
                    } catch (Exception e) {
                        w = 8.43f;
                    }
                    if ("1".equals(col.attr("hidden"))) w = 0;
                    for (int i = Math.max(1, min); i <= max; i++) widths[i] = w;
                }
            }
            out.append("<table class=\"grid\"><colgroup><col style=\"width:36px\">");
            for (int c = 1; c <= maxCol; c++) {
                out.append("<col style=\"width:").append(Math.round(widths[c] * 7 + 5)).append("px\">");
            }
            out.append("</colgroup><thead><tr><th class=\"c\"></th>");
            for (int c = 1; c <= maxCol; c++) out.append("<th>").append(letters(c)).append("</th>");
            out.append("</tr></thead><tbody>");
            for (int r = 1; r <= maxRow; r++) {
                Float h = rowHeights.get(r);
                out.append("<tr");
                if (h != null) out.append(" style=\"height:").append(Math.round(h * 4 / 3)).append("px\"");
                out.append("><th>").append(r).append("</th>");
                for (int c = 1; c <= maxCol; c++) {
                    long k = key(r, c);
                    if (covered.contains(k)) continue;
                    X cell = cells.get(k);
                    int[] merge = merges.get(k);
                    String ref = letters(c) + r;
                    out.append("<td");
                    if (merge != null) {
                        if (merge[0] > 1) out.append(" rowspan=\"").append(merge[0]).append('"');
                        if (merge[1] > 1) out.append(" colspan=\"").append(merge[1]).append('"');
                    }
                    renderCell(cell, sheetIndex, ref, out);
                    out.append("</td>");
                }
                out.append("</tr>");
            }
            out.append("</tbody></table>");
            if (truncated) out.append("<div class=\"note\">").append(esc(LocaleController.formatString(R.string.UMessageOfficeTruncated, MAX_ROWS, MAX_COLS))).append("</div>");
        }

        void renderCell(X cell, int sheetIndex, String ref, StringBuilder out) {
            String type = cell == null ? null : cell.attr("t");
            boolean formula = cell != null && cell.child("f") != null;
            int[] xf = null;
            if (cell != null && cell.attr("s") != null) {
                int s = parseInt(cell.attr("s"), 0);
                if (s >= 0 && s < xfs.size()) xf = xfs.get(s);
            }
            String value = "";
            boolean number = false;
            if (cell != null) {
                X v = cell.child("v");
                String raw = v == null ? null : v.text();
                if ("s".equals(type)) {
                    int i = parseInt(raw, -1);
                    value = i >= 0 && i < shared.size() ? shared.get(i) : "";
                } else if ("inlineStr".equals(type)) {
                    X is = cell.child("is");
                    if (is != null) {
                        StringBuilder sb = new StringBuilder();
                        for (X t : is.children("t")) sb.append(t.text());
                        for (X r : is.children("r")) if (r.child("t") != null) sb.append(r.child("t").text());
                        value = sb.toString();
                    }
                } else if ("b".equals(type)) {
                    value = "1".equals(raw) ? "TRUE" : "FALSE";
                } else if ("str".equals(type) || "e".equals(type)) {
                    value = raw == null ? "" : raw;
                } else if (raw != null) {
                    number = true;
                    value = formatNumber(raw, xf == null ? 0 : xf[0]);
                }
            }
            StringBuilder css = new StringBuilder();
            String cls = number ? "n" : "";
            if (xf != null) {
                if (xf[1] >= 0 && xf[1] < fontCss.size()) css.append(fontCss.get(xf[1]));
                if (xf[2] >= 0 && xf[2] < fillCss.size()) css.append(fillCss.get(xf[2]));
                if (xf[3] == 1) css.append("text-align:left;");
                else if (xf[3] == 2) css.append("text-align:center;");
                else if (xf[3] == 3) css.append("text-align:right;");
                if (xf[4] == 1) cls += " w";
            }
            if (formula) cls += " f";
            if (!cls.trim().isEmpty()) out.append(" class=\"").append(cls.trim()).append('"');
            if (css.length() > 0) out.append(" style=\"").append(css).append('"');
            if (!formula && !"e".equals(type)) out.append(" data-e=\"c:").append(sheetIndex).append(':').append(ref).append('"');
            out.append('>').append(esc(value));
        }

        String formatNumber(String raw, int numFmtId) {
            double d;
            try {
                d = Double.parseDouble(raw);
            } catch (Exception e) {
                return raw;
            }
            String code = formats.get(numFmtId);
            if (code == null) code = builtinFormat(numFmtId);
            return applyFormat(d, code);
        }
    }

    static String builtinFormat(int id) {
        switch (id) {
            case 1: return "0";
            case 2: return "0.00";
            case 3: return "#,##0";
            case 4: return "#,##0.00";
            case 9: return "0%";
            case 10: return "0.00%";
            case 11: return "0.00E+00";
            case 14: case 15: case 16: case 17: return "dd.mm.yyyy";
            case 18: case 19: case 20: case 21: case 45: case 46: case 47: return "hh:mm";
            case 22: return "dd.mm.yyyy hh:mm";
            case 37: case 38: return "#,##0";
            case 39: case 40: return "#,##0.00";
            case 49: return "@";
            default: return "General";
        }
    }

    static String applyFormat(double d, String code) {
        if (code == null || code.equalsIgnoreCase("General") || code.equals("@")) return general(d);
        String section = code.split(";")[0];
        String clean = section.replaceAll("\"[^\"]*\"", "").replaceAll("\\[[^\\]]*\\]", "").replaceAll("\\\\.", "").replace("_)", "");
        String lower = clean.toLowerCase(Locale.ROOT);
        boolean date = lower.contains("y") || lower.contains("d") || (lower.contains("m") && !lower.contains("0") && !lower.contains("#"));
        boolean time = lower.contains("h") || lower.contains("s");
        if (date || time) {
            if (d < 0 || d > 2958465) return general(d);
            Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            cal.clear();
            cal.set(1899, Calendar.DECEMBER, 30, 0, 0, 0);
            long millis = cal.getTimeInMillis() + Math.round(d * 86400000d);
            cal.setTimeInMillis(millis);
            StringBuilder sb = new StringBuilder();
            if (date) {
                sb.append(String.format(Locale.ROOT, "%02d.%02d.%04d", cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.YEAR)));
            }
            if (time) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(String.format(Locale.ROOT, "%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)));
                if (lower.contains("s")) sb.append(String.format(Locale.ROOT, ":%02d", cal.get(Calendar.SECOND)));
            }
            return sb.toString();
        }
        boolean percent = clean.contains("%");
        if (percent) d *= 100;
        if (lower.contains("e+")) return String.format(Locale.ROOT, "%.2E", d);
        int dot = clean.indexOf('.');
        int decimals = 0;
        if (dot >= 0) {
            for (int i = dot + 1; i < clean.length() && (clean.charAt(i) == '0' || clean.charAt(i) == '#'); i++) decimals++;
        }
        boolean grouping = clean.contains(",");
        StringBuilder pattern = new StringBuilder(grouping ? "#,##0" : "0");
        if (decimals > 0) {
            pattern.append('.');
            for (int i = 0; i < decimals; i++) pattern.append('0');
        }
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        symbols.setGroupingSeparator(' ');
        String s = new DecimalFormat(pattern.toString(), symbols).format(d);
        return percent ? s + "%" : s;
    }

    static String general(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
        return new BigDecimal(d).round(new MathContext(11)).stripTrailingZeros().toPlainString();
    }

    static boolean hasValue(X c) {
        return c.child("v") != null || c.child("is") != null || c.child("f") != null;
    }

    static long key(int r, int c) {
        return ((long) r << 20) | c;
    }

    static int colOf(String ref) {
        int col = 0;
        for (int i = 0; i < ref.length(); i++) {
            char ch = Character.toUpperCase(ref.charAt(i));
            if (ch < 'A' || ch > 'Z') break;
            col = col * 26 + (ch - 'A' + 1);
        }
        return col;
    }

    static int rowOf(String ref) {
        int i = 0;
        while (i < ref.length() && Character.isLetter(ref.charAt(i))) i++;
        return parseInt(ref.substring(i), 0);
    }

    // ===================================================================== PowerPoint

    static final class PptxRenderer {
        final ZipFile zip;
        final Images images;
        long slideWidth = 12192000, slideHeight = 6858000;

        PptxRenderer(ZipFile zip) {
            this.zip = zip;
            this.images = new Images(zip);
        }

        void render(StringBuilder out) throws Exception {
            X pres = xml(zip, "ppt/presentation.xml");
            if (pres == null) throw new UnsupportedException("no presentation");
            HashMap<String, String> rels = rels(zip, "ppt/presentation.xml");
            X size = pres.find("sldSz");
            if (size != null) {
                slideWidth = Math.max(1, parseLong(size.attr("cx")));
                slideHeight = Math.max(1, parseLong(size.attr("cy")));
            }
            X list = pres.find("sldIdLst");
            int number = 0;
            if (list != null) {
                for (X id : list.children("sldId")) {
                    String path = rels.get(id.attr("id"));
                    if (path == null) continue;
                    number++;
                    try {
                        renderSlide(path, out);
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                    out.append("<div class=\"num\">").append(number).append("</div>");
                }
            }
        }

        void renderSlide(String path, StringBuilder out) throws Exception {
            X slide = xml(zip, path);
            if (slide == null) return;
            HashMap<String, String> rels = rels(zip, path);
            // placeholders take their position from the layout, which takes it from the master
            ArrayList<X> inherited = new ArrayList<>();
            HashMap<String, String> layoutRels = null;
            for (String target : rels.values()) {
                if (target.contains("slideLayouts/")) {
                    X layout = xml(zip, target);
                    if (layout != null) {
                        layout.findAll("sp", inherited);
                        layoutRels = rels(zip, target);
                    }
                }
            }
            if (layoutRels != null) {
                for (String target : layoutRels.values()) {
                    if (target.contains("slideMasters/")) {
                        X master = xml(zip, target);
                        if (master != null) master.findAll("sp", inherited);
                    }
                }
            }
            float ptWidth = slideWidth / 12700f;
            out.append("<div class=\"slide\" data-r=\"").append((float) slideHeight / slideWidth).append("\" data-w=\"").append(ptWidth).append('"');
            X bg = slide.find("bg");
            String bgColor = null;
            if (bg != null) {
                X clr = bg.find("srgbClr");
                if (clr != null) bgColor = color(clr.attr("val"));
            }
            if (bgColor != null) out.append(" style=\"background:").append(bgColor).append('"');
            out.append('>');
            X tree = slide.find("spTree");
            if (tree != null) renderShapes(tree, rels, inherited, new double[]{0, 0, 1, 1}, out);
            out.append("</div>");
        }

        /** transform = {offsetX, offsetY, scaleX, scaleY} from a group's child space to the slide. */
        void renderShapes(X tree, HashMap<String, String> rels, ArrayList<X> inherited, double[] t, StringBuilder out) {
            for (Object o : tree.k) {
                if (!(o instanceof X)) continue;
                X x = (X) o;
                switch (x.name) {
                    case "sp": case "cxnSp": renderShape(x, inherited, t, out); break;
                    case "pic": renderPicture(x, rels, t, out); break;
                    case "graphicFrame": renderFrame(x, t, out); break;
                    case "grpSp": {
                        X xfrm = x.child("grpSpPr") == null ? null : x.child("grpSpPr").child("xfrm");
                        double[] inner = t;
                        if (xfrm != null && xfrm.child("off") != null && xfrm.child("ext") != null && xfrm.child("chOff") != null && xfrm.child("chExt") != null) {
                            double ox = parseLong(xfrm.child("off").attr("x")), oy = parseLong(xfrm.child("off").attr("y"));
                            double ex = parseLong(xfrm.child("ext").attr("cx")), ey = parseLong(xfrm.child("ext").attr("cy"));
                            double cox = parseLong(xfrm.child("chOff").attr("x")), coy = parseLong(xfrm.child("chOff").attr("y"));
                            double cex = Math.max(1, parseLong(xfrm.child("chExt").attr("cx"))), cey = Math.max(1, parseLong(xfrm.child("chExt").attr("cy")));
                            double sx = ex / cex, sy = ey / cey;
                            inner = new double[]{t[0] + t[2] * (ox - cox * sx), t[1] + t[3] * (oy - coy * sy), t[2] * sx, t[3] * sy};
                        }
                        renderShapes(x, rels, inherited, inner, out);
                        break;
                    }
                    case "AlternateContent": {
                        X choice = x.child("Choice");
                        if (choice == null) choice = x.child("Fallback");
                        if (choice != null) renderShapes(choice, rels, inherited, t, out);
                        break;
                    }
                    default: break;
                }
            }
        }

        long[] box(X owner, ArrayList<X> inherited) {
            X xfrm = owner.find("xfrm");
            if ((xfrm == null || xfrm.child("off") == null) && inherited != null) {
                X ph = owner.find("ph");
                if (ph != null) {
                    String type = ph.attr("type") == null ? "body" : ph.attr("type");
                    String idx = ph.attr("idx");
                    for (X s : inherited) {
                        X sph = s.find("ph");
                        if (sph == null) continue;
                        String stype = sph.attr("type") == null ? "body" : sph.attr("type");
                        boolean match = idx != null && idx.equals(sph.attr("idx")) || stype.equals(type)
                                || (type.equals("ctrTitle") && stype.equals("title")) || (type.equals("subTitle") && stype.equals("body"));
                        if (match) {
                            X sx = s.find("xfrm");
                            if (sx != null && sx.child("off") != null) {
                                xfrm = sx;
                                break;
                            }
                        }
                    }
                }
            }
            if (xfrm == null || xfrm.child("off") == null || xfrm.child("ext") == null) return null;
            return new long[]{parseLong(xfrm.child("off").attr("x")), parseLong(xfrm.child("off").attr("y")),
                    parseLong(xfrm.child("ext").attr("cx")), parseLong(xfrm.child("ext").attr("cy"))};
        }

        void position(long[] b, double[] t, StringBuilder out) {
            double x = t[0] + t[2] * b[0], y = t[1] + t[3] * b[1], w = t[2] * b[2], h = t[3] * b[3];
            out.append("left:").append(pct(x / slideWidth)).append("%;top:").append(pct(y / slideHeight))
                    .append("%;width:").append(pct(w / slideWidth)).append("%;height:").append(pct(h / slideHeight)).append("%;");
        }

        void renderShape(X sp, ArrayList<X> inherited, double[] t, StringBuilder out) {
            long[] b = box(sp, inherited);
            X body = sp.child("txBody");
            X spPr = sp.child("spPr");
            String fill = null;
            if (spPr != null && spPr.child("solidFill") != null) {
                X clr = spPr.child("solidFill").child("srgbClr");
                if (clr != null) fill = color(clr.attr("val"));
            }
            boolean hasText = body != null && !body.text().trim().isEmpty();
            if (b == null || (!hasText && fill == null)) return;
            out.append("<div class=\"sp\" style=\"");
            position(b, t, out);
            if (fill != null) out.append("background:").append(fill).append(';');
            X bodyPr = body == null ? null : body.child("bodyPr");
            String anchor = bodyPr == null ? null : bodyPr.attr("anchor");
            if ("ctr".equals(anchor) || "b".equals(anchor)) {
                out.append("display:flex;flex-direction:column;justify-content:").append("ctr".equals(anchor) ? "center" : "flex-end").append(';');
            }
            out.append("\">");
            if (body != null) {
                X ph = sp.find("ph");
                String phType = ph == null ? null : ph.attr("type");
                int defaultSize = "title".equals(phType) || "ctrTitle".equals(phType) ? 40 : 18;
                for (X p : body.children("p")) renderTextParagraph(p, defaultSize, out);
            }
            out.append("</div>");
        }

        void renderTextParagraph(X p, int defaultSize, StringBuilder out) {
            X pPr = p.child("pPr");
            String algn = pPr == null ? null : pPr.attr("algn");
            out.append("<p style=\"");
            if ("ctr".equals(algn)) out.append("text-align:center;");
            else if ("r".equals(algn)) out.append("text-align:right;");
            else if ("just".equals(algn)) out.append("text-align:justify;");
            int size = defaultSize;
            X end = p.child("endParaRPr");
            if (end != null && end.attr("sz") != null) size = parseInt(end.attr("sz"), defaultSize * 100) / 100;
            out.append("font-size:calc(var(--k,1) * ").append(size).append("px)\">");
            boolean bullet = pPr != null && pPr.child("buChar") != null;
            if (bullet) out.append(esc(pPr.child("buChar").attr("char"))).append(" ");
            for (Object o : p.k) {
                if (!(o instanceof X)) continue;
                X x = (X) o;
                if (x.name.equals("br")) {
                    out.append("<br>");
                    continue;
                }
                if (!x.name.equals("r") && !x.name.equals("fld")) continue;
                X rPr = x.child("rPr");
                X tx = x.child("t");
                String text = tx == null ? "" : tx.text();
                if (text.isEmpty()) continue;
                StringBuilder css = new StringBuilder();
                if (rPr != null) {
                    if ("1".equals(rPr.attr("b"))) css.append("font-weight:bold;");
                    if ("1".equals(rPr.attr("i"))) css.append("font-style:italic;");
                    if (rPr.attr("u") != null && !"none".equals(rPr.attr("u"))) css.append("text-decoration:underline;");
                    int sz = parseInt(rPr.attr("sz"), 0);
                    if (sz > 0) css.append("font-size:calc(var(--k,1) * ").append(sz / 100f).append("px);");
                    X fill = rPr.child("solidFill");
                    X clr = fill == null ? null : fill.child("srgbClr");
                    String c = clr == null ? null : color(clr.attr("val"));
                    if (c != null) css.append("color:").append(c).append(';');
                }
                if (css.length() > 0) out.append("<span style=\"").append(css).append("\">");
                out.append(esc(text));
                if (css.length() > 0) out.append("</span>");
            }
            out.append("</p>");
        }

        void renderPicture(X pic, HashMap<String, String> rels, double[] t, StringBuilder out) {
            long[] b = box(pic, null);
            X blip = pic.find("blip");
            String uri = blip == null ? null : images.dataUri(rels.get(blip.attr("embed")));
            if (b == null || uri == null) return;
            out.append("<img style=\"");
            position(b, t, out);
            out.append("\" src=\"").append(uri).append("\">");
        }

        void renderFrame(X frame, double[] t, StringBuilder out) {
            X tbl = frame.find("tbl");
            long[] b = box(frame, null);
            if (tbl == null || b == null) return;
            out.append("<div class=\"sp\" style=\"");
            position(b, t, out);
            out.append("\"><table style=\"width:100%;border-collapse:collapse;font-size:calc(var(--k,1) * 14px)\">");
            for (X tr : tbl.children("tr")) {
                out.append("<tr>");
                for (X tc : tr.children("tc")) {
                    if ("1".equals(tc.attr("hMerge")) || "1".equals(tc.attr("vMerge"))) continue;
                    out.append("<td style=\"border:1px solid #bbb;padding:2px 4px\"");
                    int span = parseInt(tc.attr("gridSpan"), 1);
                    if (span > 1) out.append(" colspan=\"").append(span).append('"');
                    out.append('>');
                    X body = tc.child("txBody");
                    if (body != null) for (X p : body.children("p")) renderTextParagraph(p, 14, out);
                    out.append("</td>");
                }
                out.append("</tr>");
            }
            out.append("</table></div>");
        }
    }

    static String pct(double v) {
        return String.format(Locale.ROOT, "%.3f", v * 100);
    }

    // ===================================================================== CSV and text

    static final class Text {
        String text;
        Charset charset;
        boolean bom;
    }

    static Text readText(File file) throws IOException {
        if (file.length() > MAX_TEXT) throw new IOException("too large");
        byte[] data = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            int off = 0, n;
            while (off < data.length && (n = in.read(data, off, data.length - off)) > 0) off += n;
        }
        Text t = new Text();
        int start = 0;
        if (data.length >= 3 && (data[0] & 0xff) == 0xEF && (data[1] & 0xff) == 0xBB && (data[2] & 0xff) == 0xBF) {
            t.bom = true;
            start = 3;
        }
        try {
            t.text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data, start, data.length - start)).toString();
            t.charset = StandardCharsets.UTF_8;
        } catch (CharacterCodingException e) {
            // old Cyrillic files from Windows
            t.charset = Charset.forName("windows-1251");
            t.text = new String(data, start, data.length - start, t.charset);
        }
        return t;
    }

    static char delimiter(String text) {
        int line = text.indexOf('\n');
        String first = line < 0 ? text : text.substring(0, line);
        int commas = count(first, ','), semis = count(first, ';'), tabs = count(first, '\t');
        if (tabs >= commas && tabs >= semis && tabs > 0) return '\t';
        return semis > commas ? ';' : ',';
    }

    static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }

    static ArrayList<ArrayList<String>> parseCsv(String text, char sep) {
        ArrayList<ArrayList<String>> rows = new ArrayList<>();
        ArrayList<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"' && field.length() == 0) {
                quoted = true;
            } else if (c == sep) {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }

    static void renderCsv(File file, StringBuilder out) throws IOException {
        String text = readText(file).text;
        ArrayList<ArrayList<String>> rows = parseCsv(text, delimiter(text));
        int cols = 1;
        for (ArrayList<String> r : rows) cols = Math.max(cols, r.size());
        boolean truncated = rows.size() > MAX_ROWS || cols > MAX_COLS;
        int rowCount = Math.min(rows.size() + 3, MAX_ROWS);
        cols = Math.min(cols + 1, MAX_COLS);
        out.append("<div class=\"sheet on\"><table class=\"grid\"><colgroup><col style=\"width:36px\">");
        for (int c = 0; c < cols; c++) out.append("<col style=\"width:120px\">");
        out.append("</colgroup><thead><tr><th class=\"c\"></th>");
        for (int c = 1; c <= cols; c++) out.append("<th>").append(letters(c)).append("</th>");
        out.append("</tr></thead><tbody>");
        for (int r = 0; r < rowCount; r++) {
            ArrayList<String> row = r < rows.size() ? rows.get(r) : null;
            out.append("<tr><th>").append(r + 1).append("</th>");
            for (int c = 0; c < cols; c++) {
                String v = row != null && c < row.size() ? row.get(c) : "";
                out.append("<td").append(v.matches("-?[\\d.,\\s]+") ? " class=\"n\"" : "").append(" data-e=\"v:").append(r).append(':').append(c).append("\">")
                        .append(esc(v)).append("</td>");
            }
            out.append("</tr>");
        }
        out.append("</tbody></table></div>");
        if (truncated) out.append("<div class=\"note\">").append(esc(LocaleController.formatString(R.string.UMessageOfficeTruncated, MAX_ROWS, MAX_COLS))).append("</div>");
    }

    // ===================================================================== saving

    /**
     * Applies the page's edits ({@code [{"k":"p:12","v":"text"}, ...]}) to {@code source} and writes the result
     * to {@code out}. Every untouched part of the file is copied unchanged.
     */
    public static void save(File source, int kind, JSONArray edits, File out) throws Exception {
        HashMap<String, String> map = new HashMap<>();
        for (int i = 0; i < edits.length(); i++) {
            JSONObject e = edits.getJSONObject(i);
            map.put(e.getString("k"), clean(e.optString("v")));
        }
        switch (kind) {
            case DOCX: saveDocx(source, map, out); break;
            case XLSX: saveXlsx(source, map, out); break;
            case CSV: saveCsv(source, map, out); break;
            case TXT: saveTxt(source, map, out); break;
            case DOC: UMessageOfficeLegacy.saveDocAsDocx(source, map, out); break;
            case XLS: UMessageOfficeLegacy.saveXlsAsXlsx(source, map, out); break;
            default: throw new UnsupportedException("read only");
        }
    }

    /** contenteditable leaves no-break spaces and a trailing newline; XML can't hold control characters. */
    static String clean(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ') c = ' ';
            if (c == '\r') continue;
            if (c < 0x20 && c != '\n' && c != '\t') continue;
            if (c == '￾' || c == '￿') continue;
            sb.append(c);
        }
        return sb.toString();
    }

    static String xmlEsc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static void writeZip(File source, HashMap<String, byte[]> replaced, File out) throws IOException {
        try (ZipFile zip = new ZipFile(source); ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            byte[] buffer = new byte[16384];
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                zos.putNextEntry(new ZipEntry(entry.getName()));
                byte[] data = replaced.get(entry.getName());
                if (data != null) {
                    zos.write(data);
                } else {
                    try (InputStream in = zip.getInputStream(entry)) {
                        int n;
                        while ((n = in.read(buffer)) > 0) zos.write(buffer, 0, n);
                    }
                }
                zos.closeEntry();
            }
        }
    }

    // ---------- Word

    private static void saveDocx(File source, HashMap<String, String> edits, File out) throws Exception {
        String main = "word/document.xml";
        String xml;
        try (ZipFile zip = new ZipFile(source)) {
            X types = xml(zip, "[Content_Types].xml");
            if (types != null && types.find("Types") != null) {
                for (X o : types.find("Types").children("Override")) {
                    String ct = o.attr("ContentType");
                    if (ct != null && ct.contains("wordprocessingml.document.main")) main = o.attr("PartName").substring(1);
                }
            }
            byte[] data = read(zip, main);
            if (data == null) throw new UnsupportedException("no document");
            xml = new String(data, StandardCharsets.UTF_8);
        }
        Matcher ns = Pattern.compile("xmlns:([A-Za-z_][\\w.\\-]*)=\"" + Pattern.quote(W) + "\"").matcher(xml);
        if (!ns.find()) throw new UnsupportedException("namespace");
        String w = ns.group(1) + ":";
        // paragraph starts in document order: the same numbering the page was built with
        Pattern start = Pattern.compile("<" + Pattern.quote(w) + "p(?=[\\s/>])");
        ArrayList<Integer> starts = new ArrayList<>();
        Matcher m = start.matcher(xml);
        while (m.find()) starts.add(m.start());
        // edit from the end so earlier offsets stay valid
        ArrayList<Integer> indices = new ArrayList<>();
        for (String k : edits.keySet()) {
            if (k.startsWith("p:")) indices.add(Integer.parseInt(k.substring(2)));
        }
        java.util.Collections.sort(indices, java.util.Collections.reverseOrder());
        StringBuilder sb = new StringBuilder(xml);
        for (int index : indices) {
            if (index < 0 || index >= starts.size()) continue;
            int from = starts.get(index);
            int to = elementEnd(sb, from);
            if (to < 0) continue;
            String rebuilt = rebuildParagraph(sb.substring(from, to), w, edits.get("p:" + index));
            sb.replace(from, to, rebuilt);
        }
        HashMap<String, byte[]> replaced = new HashMap<>();
        replaced.put(main, sb.toString().getBytes(StandardCharsets.UTF_8));
        writeZip(source, replaced, out);
    }

    /** Keeps the paragraph's properties, pictures and bookmarks; its text becomes one run in the first run's style. */
    static String rebuildParagraph(String p, String w, String text) {
        int openEnd = tagEnd(p, 0);
        if (openEnd < 0) return p;
        String openTag = p.substring(0, openEnd);
        if (openTag.endsWith("/>")) openTag = openTag.substring(0, openTag.length() - 2) + ">";
        StringBuilder pPr = new StringBuilder();
        StringBuilder keptBefore = new StringBuilder();
        StringBuilder keptAfter = new StringBuilder();
        String rPr = null;
        boolean seenText = false;
        if (!p.substring(0, openEnd).endsWith("/>")) {
            int i = openEnd;
            int close = p.lastIndexOf("</" + w + "p>");
            while (i < close) {
                int lt = p.indexOf('<', i);
                if (lt < 0 || lt >= close) break;
                int end = elementEnd(p, lt);
                if (end < 0) break;
                String child = p.substring(lt, end);
                String name = tagName(child);
                if (name.equals(w + "pPr")) {
                    pPr.append(child);
                } else if (name.equals(w + "r")) {
                    boolean picture = child.contains("<" + w + "drawing") || child.contains("<" + w + "pict") || child.contains("<" + w + "object")
                            || child.contains("AlternateContent");
                    boolean hasText = child.contains("<" + w + "t>") || child.contains("<" + w + "t ");
                    if (hasText && rPr == null) {
                        int rOpen = tagEnd(child, 0);
                        int r = child.indexOf("<" + w + "rPr", rOpen);
                        if (r == rOpen || r >= 0 && child.substring(rOpen, r).trim().isEmpty()) {
                            int rEnd = elementEnd(child, r);
                            if (rEnd > 0) rPr = child.substring(r, rEnd);
                        }
                    }
                    if (hasText) seenText = true;
                    if (picture) {
                        // the picture stays where it was relative to the text
                        String stripped = child.replaceAll("<" + Pattern.quote(w) + "t(?:\\s[^>]*)?>[^<]*</" + Pattern.quote(w) + "t>", "");
                        (seenText ? keptAfter : keptBefore).append(stripped);
                    }
                } else if (name.equals(w + "bookmarkStart") || name.equals(w + "bookmarkEnd")
                        || name.equals(w + "commentRangeStart") || name.equals(w + "commentRangeEnd")) {
                    keptBefore.append(child);
                }
                // hyperlinks, fields, tracked changes, smart tags: their text is in the new run
                i = end;
            }
        }
        StringBuilder run = new StringBuilder();
        if (!text.isEmpty()) {
            run.append('<').append(w).append("r>");
            if (rPr != null) run.append(rPr);
            StringBuilder segment = new StringBuilder();
            for (int i = 0; i <= text.length(); i++) {
                char c = i < text.length() ? text.charAt(i) : 0;
                if (i == text.length() || c == '\n' || c == '\t') {
                    if (segment.length() > 0) {
                        run.append('<').append(w).append("t xml:space=\"preserve\">").append(xmlEsc(segment.toString())).append("</").append(w).append("t>");
                        segment.setLength(0);
                    }
                    if (c == '\n') run.append('<').append(w).append("br/>");
                    else if (c == '\t') run.append('<').append(w).append("tab/>");
                } else {
                    segment.append(c);
                }
            }
            run.append("</").append(w).append("r>");
        }
        return openTag + pPr + keptBefore + run + keptAfter + "</" + w + "p>";
    }

    /** Index just past the tag starting at {@code lt}, or -1. */
    static int tagEnd(CharSequence s, int lt) {
        boolean quote = false;
        char q = 0;
        for (int i = lt + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote) {
                if (c == q) quote = false;
            } else if (c == '"' || c == '\'') {
                quote = true;
                q = c;
            } else if (c == '>') {
                return i + 1;
            }
        }
        return -1;
    }

    static String tagName(String element) {
        int i = 1;
        while (i < element.length() && !Character.isWhitespace(element.charAt(i)) && element.charAt(i) != '>' && element.charAt(i) != '/') i++;
        return element.substring(1, i);
    }

    /** Index just past the element whose start tag begins at {@code lt}, nested elements included; -1 if broken. */
    static int elementEnd(CharSequence s, int lt) {
        int depth = 0;
        int i = lt;
        while (i < s.length()) {
            if (s.charAt(i) != '<') {
                i++;
                continue;
            }
            if (startsWith(s, i, "<!--")) {
                int e = indexOf(s, "-->", i);
                if (e < 0) return -1;
                i = e + 3;
                continue;
            }
            if (startsWith(s, i, "<![CDATA[")) {
                int e = indexOf(s, "]]>", i);
                if (e < 0) return -1;
                i = e + 3;
                continue;
            }
            int end = tagEnd(s, i);
            if (end < 0) return -1;
            char next = i + 1 < s.length() ? s.charAt(i + 1) : 0;
            if (next == '?' || next == '!') {
                i = end;
                continue;
            }
            if (next == '/') depth--;
            else if (s.charAt(end - 2) != '/') depth++;
            i = end;
            if (depth == 0) return i;
        }
        return -1;
    }

    static boolean startsWith(CharSequence s, int at, String prefix) {
        if (at + prefix.length() > s.length()) return false;
        for (int i = 0; i < prefix.length(); i++) if (s.charAt(at + i) != prefix.charAt(i)) return false;
        return true;
    }

    static int indexOf(CharSequence s, String what, int from) {
        return s.toString().indexOf(what, from);
    }

    // ---------- Excel

    private static void saveXlsx(File source, HashMap<String, String> edits, File out) throws Exception {
        HashMap<String, byte[]> replaced = new HashMap<>();
        try (ZipFile zip = new ZipFile(source)) {
            X workbook = xml(zip, "xl/workbook.xml");
            if (workbook == null) throw new UnsupportedException("no workbook");
            HashMap<String, String> rels = rels(zip, "xl/workbook.xml");
            X sheetsNode = workbook.find("sheets");
            ArrayList<X> sheets = sheetsNode == null ? new ArrayList<X>() : sheetsNode.children("sheet");
            HashMap<Integer, ArrayList<String[]>> bySheet = new HashMap<>();
            for (java.util.Map.Entry<String, String> e : edits.entrySet()) {
                String[] parts = e.getKey().split(":");
                if (parts.length != 3 || !parts[0].equals("c")) continue;
                int sheet = Integer.parseInt(parts[1]);
                ArrayList<String[]> list = bySheet.get(sheet);
                if (list == null) bySheet.put(sheet, list = new ArrayList<>());
                list.add(new String[]{parts[2], e.getValue()});
            }
            for (java.util.Map.Entry<Integer, ArrayList<String[]>> e : bySheet.entrySet()) {
                int index = e.getKey();
                if (index < 0 || index >= sheets.size()) continue;
                String path = rels.get(sheets.get(index).attr("id"));
                byte[] data = read(zip, path);
                if (data == null) continue;
                StringBuilder sheetXml = new StringBuilder(new String(data, StandardCharsets.UTF_8));
                for (String[] edit : e.getValue()) setCell(sheetXml, edit[0], edit[1]);
                replaced.put(path, sheetXml.toString().getBytes(StandardCharsets.UTF_8));
            }
            // formulas depending on edited cells: let Excel recalculate when it opens the file
            byte[] wb = read(zip, "xl/workbook.xml");
            if (wb != null) {
                String text = new String(wb, StandardCharsets.UTF_8);
                Matcher calc = Pattern.compile("<((?:[\\w]+:)?calcPr)\\b([^>]*?)(/?)>").matcher(text);
                if (calc.find() && !calc.group(2).contains("fullCalcOnLoad")) {
                    String tag = "<" + calc.group(1) + calc.group(2) + " fullCalcOnLoad=\"1\"" + calc.group(3) + ">";
                    replaced.put("xl/workbook.xml", (text.substring(0, calc.start()) + tag + text.substring(calc.end())).getBytes(StandardCharsets.UTF_8));
                }
            }
        }
        writeZip(source, replaced, out);
    }

    static void setCell(StringBuilder xml, String ref, String value) throws UnsupportedException {
        Matcher root = Pattern.compile("<(\\w+:)?worksheet[\\s>]").matcher(xml);
        String x = root.find() && root.group(1) != null ? root.group(1) : "";
        int row = rowOf(ref), col = colOf(ref);
        if (row <= 0 || col <= 0) return;
        int sdOpen = indexOf(xml, "<" + x + "sheetData", 0);
        if (sdOpen < 0) throw new UnsupportedException("no sheetData");
        int sdOpenEnd = tagEnd(xml, sdOpen);
        if (xml.charAt(sdOpenEnd - 2) == '/') {
            xml.replace(sdOpen, sdOpenEnd, "<" + x + "sheetData></" + x + "sheetData>");
            sdOpenEnd = sdOpen + x.length() + 11;
        }
        int sdClose = indexOf(xml, "</" + x + "sheetData>", sdOpenEnd);
        Pattern rowStart = Pattern.compile("<" + Pattern.quote(x) + "row(?=[\\s/>])");
        Matcher m = rowStart.matcher(xml);
        int at = sdOpenEnd;
        while (m.find(at) && m.start() < sdClose) {
            int open = m.start();
            int openEnd = tagEnd(xml, open);
            int r = parseInt(attrOf(xml.substring(open, openEnd), "r"), -1);
            if (r < 0) throw new UnsupportedException("row without number");
            int end = elementEnd(xml, open);
            if (r == row) {
                setCellInRow(xml, open, openEnd, end, x, ref, col, value);
                return;
            }
            if (r > row) {
                if (!value.isEmpty()) xml.insert(open, "<" + x + "row r=\"" + row + "\">" + cellXml(x, ref, null, value) + "</" + x + "row>");
                return;
            }
            at = end;
        }
        if (!value.isEmpty()) xml.insert(sdClose, "<" + x + "row r=\"" + row + "\">" + cellXml(x, ref, null, value) + "</" + x + "row>");
    }

    static void setCellInRow(StringBuilder xml, int open, int openEnd, int end, String x, String ref, int col, String value) throws UnsupportedException {
        if (xml.charAt(openEnd - 2) == '/') {
            if (value.isEmpty()) return;
            String tag = xml.substring(open, openEnd - 2) + ">";
            xml.replace(open, openEnd, tag + cellXml(x, ref, null, value) + "</" + x + "row>");
            return;
        }
        int close = end - ("</" + x + "row>").length();
        Pattern cellStart = Pattern.compile("<" + Pattern.quote(x) + "c(?=[\\s/>])");
        Matcher m = cellStart.matcher(xml);
        int at = openEnd;
        while (m.find(at) && m.start() < close) {
            int c0 = m.start();
            int cTagEnd = tagEnd(xml, c0);
            String tag = xml.substring(c0, cTagEnd);
            String r = attrOf(tag, "r");
            if (r == null) throw new UnsupportedException("cell without reference");
            int c = colOf(r);
            int cEnd = elementEnd(xml, c0);
            if (c == col) {
                xml.replace(c0, cEnd, cellXml(x, ref, attrOf(tag, "s"), value));
                return;
            }
            if (c > col) {
                if (!value.isEmpty()) xml.insert(c0, cellXml(x, ref, null, value));
                return;
            }
            at = cEnd;
        }
        if (!value.isEmpty()) xml.insert(close, cellXml(x, ref, null, value));
    }

    static String attrOf(String tag, String name) {
        Matcher m = Pattern.compile("\\s" + Pattern.quote(name) + "=\"([^\"]*)\"").matcher(tag);
        return m.find() ? m.group(1) : null;
    }

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?");

    static String cellXml(String x, String ref, String style, String value) {
        String s = style == null ? "" : " s=\"" + style + "\"";
        String v = value.trim();
        if (v.isEmpty()) return "<" + x + "c r=\"" + ref + "\"" + s + "/>";
        if (NUMBER.matcher(v).matches()) return "<" + x + "c r=\"" + ref + "\"" + s + "><" + x + "v>" + v + "</" + x + "v></" + x + "c>";
        return "<" + x + "c r=\"" + ref + "\"" + s + " t=\"inlineStr\"><" + x + "is><" + x + "t xml:space=\"preserve\">" + xmlEsc(value) + "</" + x + "t></" + x + "is></" + x + "c>";
    }

    // ---------- CSV and text

    private static void saveCsv(File source, HashMap<String, String> edits, File out) throws IOException {
        Text t = readText(source);
        char sep = delimiter(t.text);
        ArrayList<ArrayList<String>> rows = parseCsv(t.text, sep);
        for (java.util.Map.Entry<String, String> e : edits.entrySet()) {
            String[] parts = e.getKey().split(":");
            if (parts.length != 3 || !parts[0].equals("v")) continue;
            int r = Integer.parseInt(parts[1]), c = Integer.parseInt(parts[2]);
            while (rows.size() <= r) rows.add(new ArrayList<String>());
            ArrayList<String> row = rows.get(r);
            while (row.size() <= c) row.add("");
            row.set(c, e.getValue().replace("\n", " ").trim());
        }
        // drop the empty rows and trailing empty cells the editor added
        while (!rows.isEmpty() && isBlank(rows.get(rows.size() - 1))) rows.remove(rows.size() - 1);
        String newline = t.text.contains("\r\n") ? "\r\n" : "\n";
        StringBuilder sb = new StringBuilder();
        for (ArrayList<String> row : rows) {
            int last = row.size();
            while (last > 0 && row.get(last - 1).isEmpty()) last--;
            for (int i = 0; i < last; i++) {
                if (i > 0) sb.append(sep);
                String f = row.get(i);
                if (f.indexOf(sep) >= 0 || f.indexOf('"') >= 0 || f.indexOf('\n') >= 0) f = "\"" + f.replace("\"", "\"\"") + "\"";
                sb.append(f);
            }
            sb.append(newline);
        }
        writeText(out, sb.toString(), t);
    }

    static boolean isBlank(ArrayList<String> row) {
        for (String s : row) if (!s.isEmpty()) return false;
        return true;
    }

    private static void saveTxt(File source, HashMap<String, String> edits, File out) throws IOException {
        Text t = readText(source);
        String text = edits.containsKey("t") ? edits.get("t") : t.text;
        if (t.text.contains("\r\n")) text = text.replace("\n", "\r\n");
        writeText(out, text, t);
    }

    private static void writeText(File out, String text, Text original) throws IOException {
        try (FileOutputStream os = new FileOutputStream(out)) {
            if (original.bom) os.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            os.write(text.getBytes(original.charset));
        }
    }
}
