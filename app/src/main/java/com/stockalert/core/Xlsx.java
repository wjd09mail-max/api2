package com.stockalert.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 아주 작은 엑셀(.xlsx) 작성기 — 외부 라이브러리 없이 ZIP + XML 로 만듭니다.
 * 셀 값: String(글자), Number(숫자), Pct(등락률 +0.00), Won(금액 #,##0), null(빈칸)
 */
public final class Xlsx {
    /** 등락률/수익률 (+1.23 형식, 양수 빨강·음수 파랑) */
    public static final class Pct {
        final double v;

        public Pct(double v) {
            this.v = v;
        }
    }

    /** 금액 (#,##0, 손익이면 색) */
    public static final class Won {
        final double v;
        final boolean colored;

        public Won(double v, boolean colored) {
            this.v = v;
            this.colored = colored;
        }
    }

    private static final class Sheet {
        String name;
        List<Object[]> rows = new ArrayList<>();
        double[] widths;
        int headerRow = -1;
        String title;
    }

    private final List<Sheet> sheets = new ArrayList<>();

    /** 시트 추가: title(1행 제목, null 가능), header(머리글), rows, widths(열 너비) */
    public void sheet(String name, String title, String[] header, List<Object[]> rows, double[] widths) {
        Sheet s = new Sheet();
        s.name = name.replaceAll("[\\\\/?*\\[\\]:]", " ");
        if (s.name.length() > 31) s.name = s.name.substring(0, 31);
        s.title = title;
        if (title != null) {
            s.rows.add(new Object[]{title});
            s.rows.add(new Object[0]);
        }
        s.headerRow = s.rows.size();
        s.rows.add(header);
        s.rows.addAll(rows);
        s.widths = widths;
        sheets.add(s);
    }

    static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                default:
                    if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') continue;
                    b.append(c);
            }
        }
        return b.toString();
    }

    static String col(int i) {
        StringBuilder b = new StringBuilder();
        i++;
        while (i > 0) {
            int m = (i - 1) % 26;
            b.insert(0, (char) ('A' + m));
            i = (i - 1) / 26;
        }
        return b.toString();
    }

    // 스타일 번호: 0 기본, 1 머리글, 2 금액, 3 등락률, 4 제목, 5 손익금액(색), 6 숫자
    private static final String STYLES = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
            + "<numFmts count=\"2\"><numFmt numFmtId=\"164\" formatCode=\"[Red]+0.00;[Blue]-0.00;0.00\"/>"
            + "<numFmt numFmtId=\"165\" formatCode=\"[Red]+#,##0;[Blue]-#,##0;0\"/></numFmts>"
            + "<fonts count=\"3\"><font><sz val=\"10\"/><name val=\"맑은 고딕\"/></font>"
            + "<font><b/><sz val=\"10\"/><color rgb=\"FFFFFFFF\"/><name val=\"맑은 고딕\"/></font>"
            + "<font><b/><sz val=\"13\"/><name val=\"맑은 고딕\"/></font></fonts>"
            + "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>"
            + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF2F4A7A\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>"
            + "<borders count=\"2\"><border><left/><right/><top/><bottom/><diagonal/></border>"
            + "<border><left style=\"thin\"><color rgb=\"FFBFC7D5\"/></left><right style=\"thin\"><color rgb=\"FFBFC7D5\"/></right>"
            + "<top style=\"thin\"><color rgb=\"FFBFC7D5\"/></top><bottom style=\"thin\"><color rgb=\"FFBFC7D5\"/></bottom><diagonal/></border></borders>"
            + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
            + "<cellXfs count=\"7\">"
            + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyBorder=\"1\"/>"
            + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\" wrapText=\"1\"/></xf>"
            + "<xf numFmtId=\"3\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyBorder=\"1\"/>"
            + "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyBorder=\"1\"/>"
            + "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"
            + "<xf numFmtId=\"165\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyBorder=\"1\"/>"
            + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyBorder=\"1\"/>"
            + "</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>";

    private String sheetXml(Sheet s) {
        StringBuilder b = new StringBuilder();
        b.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
                .append("<sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr>");
        // 머리글 고정
        b.append("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"").append(s.headerRow + 1)
                .append("\" topLeftCell=\"A").append(s.headerRow + 2).append("\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>");
        if (s.widths != null && s.widths.length > 0) {
            b.append("<cols>");
            for (int i = 0; i < s.widths.length; i++)
                b.append("<col min=\"").append(i + 1).append("\" max=\"").append(i + 1).append("\" width=\"").append(s.widths[i]).append("\" customWidth=\"1\"/>");
            b.append("</cols>");
        }
        b.append("<sheetData>");
        for (int r = 0; r < s.rows.size(); r++) {
            Object[] row = s.rows.get(r);
            b.append("<row r=\"").append(r + 1).append("\"");
            if (r == s.headerRow) b.append(" ht=\"30\" customHeight=\"1\"");
            b.append(">");
            for (int c = 0; c < row.length; c++) {
                Object v = row[c];
                if (v == null) continue;
                String ref = col(c) + (r + 1);
                boolean head = r == s.headerRow, title = s.title != null && r == 0;
                if (v instanceof Pct || v instanceof Won || v instanceof Number) {
                    double d;
                    int st;
                    if (v instanceof Pct) {
                        d = Math.round(((Pct) v).v * 100) / 100.0;
                        st = 3;
                    } else if (v instanceof Won) {
                        d = Math.round(((Won) v).v);
                        st = ((Won) v).colored ? 5 : 2;
                    } else {
                        d = ((Number) v).doubleValue();
                        st = 6;
                    }
                    if (Double.isNaN(d) || Double.isInfinite(d)) continue;
                    String num = d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
                    b.append("<c r=\"").append(ref).append("\" s=\"").append(st).append("\"><v>").append(num).append("</v></c>");
                } else {
                    int st = title ? 4 : head ? 1 : 0;
                    b.append("<c r=\"").append(ref).append("\" s=\"").append(st).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                            .append(esc(String.valueOf(v))).append("</t></is></c>");
                }
            }
            b.append("</row>");
        }
        b.append("</sheetData>");
        int ncol = s.widths != null ? s.widths.length : 1;
        if (s.rows.size() > s.headerRow + 1)
            b.append("<autoFilter ref=\"A").append(s.headerRow + 1).append(":").append(col(ncol - 1)).append(s.rows.size()).append("\"/>");
        b.append("<pageMargins left=\"0.4\" right=\"0.4\" top=\"0.5\" bottom=\"0.5\" header=\"0.3\" footer=\"0.3\"/>")
                .append("<pageSetup orientation=\"landscape\" fitToWidth=\"1\" fitToHeight=\"0\"/>");
        b.append("</worksheet>");
        return b.toString();
    }

    public byte[] build() throws Exception {
        if (sheets.isEmpty()) sheet("Sheet1", null, new String[]{""}, new ArrayList<>(), null);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bo)) {
            StringBuilder ct = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                    + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
            StringBuilder wb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
            StringBuilder rel = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
            StringBuilder defs = new StringBuilder();
            for (int i = 0; i < sheets.size(); i++) {
                Sheet s = sheets.get(i);
                ct.append("<Override PartName=\"/xl/worksheets/sheet").append(i + 1)
                        .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
                wb.append("<sheet name=\"").append(esc(s.name)).append("\" sheetId=\"").append(i + 1).append("\" r:id=\"rId").append(i + 1).append("\"/>");
                rel.append("<Relationship Id=\"rId").append(i + 1)
                        .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
                        .append(i + 1).append(".xml\"/>");
                int ncol = s.widths != null ? s.widths.length : 1;
                if (s.rows.size() > s.headerRow + 1)
                    defs.append("<definedName name=\"_xlnm._FilterDatabase\" localSheetId=\"").append(i).append("\" hidden=\"1\">'")
                            .append(esc(s.name.replace("'", "''"))).append("'!$A$").append(s.headerRow + 1).append(":$")
                            .append(col(ncol - 1)).append("$").append(s.rows.size()).append("</definedName>");
            }
            ct.append("</Types>");
            wb.append("</sheets>");
            if (defs.length() > 0) wb.append("<definedNames>").append(defs).append("</definedNames>");
            wb.append("</workbook>");
            rel.append("<Relationship Id=\"rId").append(sheets.size() + 1)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>");
            put(z, "[Content_Types].xml", ct.toString());
            put(z, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
            put(z, "xl/workbook.xml", wb.toString());
            put(z, "xl/_rels/workbook.xml.rels", rel.toString());
            put(z, "xl/styles.xml", STYLES);
            for (int i = 0; i < sheets.size(); i++) put(z, "xl/worksheets/sheet" + (i + 1) + ".xml", sheetXml(sheets.get(i)));
        }
        return bo.toByteArray();
    }

    private static void put(ZipOutputStream z, String name, String body) throws Exception {
        z.putNextEntry(new ZipEntry(name));
        z.write(body.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }
}
