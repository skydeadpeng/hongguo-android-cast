package io.github.jackotom.hongguo.android;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import javax.xml.parsers.*;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

/** Bounded UTF-8 XML tree; no DTD/entity/network access, including old Android parsers. */
final class DlnaXml {
    static final class Node {
        final String name, ns; final StringBuilder text = new StringBuilder(); final List<Node> children = new ArrayList<>();
        Node(String name, String ns) { this.name = name; this.ns = ns; }
        Node child(String name) throws IOException {
            Node found = null;
            for (Node row : children) if (row.name.equals(name)) { if (found != null) throw new IOException("投屏 XML 节点重复"); found = row; }
            return found;
        }
        String value(String name) throws IOException { Node node = child(name); return node == null ? "" : node.text.toString().trim(); }
    }
    static Node parse(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > DlnaNetwork.MAX_BODY) throw new IOException("投屏 XML 大小无效");
        final String xml;
        try { xml = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException failure) { throw new IOException("投屏 XML 必须为 UTF-8", failure); }
        if (xml.indexOf('\0') >= 0 || xml.matches("(?is).*<!\\s*(?:DOCTYPE|ENTITY)\\b.*")) throw new IOException("投屏 XML 禁止 DTD 或外部实体");
        final Deque<Node> stack = new ArrayDeque<>(); final Node[] root = new Node[1]; final int[] count = { 0 };
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance(); factory.setNamespaceAware(true); factory.setValidating(false);
            // Some Android SAX factories omit these options. The mandatory lexical
            // guard and rejecting resolver above/below remain enforced in that case.
            for (String feature : Arrays.asList("http://xml.org/sax/features/external-general-entities", "http://xml.org/sax/features/external-parameter-entities", "http://apache.org/xml/features/nonvalidating/load-external-dtd")) {
                try { factory.setFeature(feature, false); } catch (ParserConfigurationException | SAXException ignored) { }
            }
            XMLReader reader = factory.newSAXParser().getXMLReader();
            reader.setEntityResolver((publicId, systemId) -> { throw new SAXException("External entity rejected"); });
            DefaultHandler handler = new DefaultHandler() {
                @Override public void startElement(String ns, String local, String qname, Attributes attributes) throws SAXException {
                    if (stack.size() >= 32 || ++count[0] > 2048 || attributes.getLength() > 32) throw new SAXException("XML bounds");
                    Node node = new Node(local.isEmpty() ? qname : local, ns);
                    if (stack.isEmpty()) { if (root[0] != null) throw new SAXException("Multiple XML roots"); root[0] = node; } else stack.peek().children.add(node);
                    stack.push(node);
                }
                @Override public void endElement(String ns, String local, String qname) { stack.pop(); }
                @Override public void characters(char[] value, int offset, int length) throws SAXException {
                    if (!stack.isEmpty()) { if (stack.peek().text.length() + length > 131072) throw new SAXException("XML text bounds"); stack.peek().text.append(value, offset, length); }
                }
                @Override public void error(SAXParseException error) throws SAXException { throw error; }
                @Override public void fatalError(SAXParseException error) throws SAXException { throw error; }
            };
            reader.setContentHandler(handler); reader.setErrorHandler(handler); reader.parse(new InputSource(new StringReader(xml)));
            if (root[0] == null) throw new IOException("投屏 XML 缺少根节点"); return root[0];
        } catch (ParserConfigurationException | SAXException failure) { throw new IOException("投屏 XML 未通过结构校验", failure); }
    }
    static String escape(String value) throws IOException {
        if (value == null || value.length() > 65536) throw new IOException("投屏文本无效");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) { if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) throw new IOException("投屏文本字符无效"); out.append(c).append(value.charAt(++i)); continue; }
            if (Character.isLowSurrogate(c) || c < 32 && c != '\t' && c != '\n' && c != '\r' || c == 0xfffe || c == 0xffff) throw new IOException("投屏文本字符无效");
            switch (c) { case '&': out.append("&amp;"); break; case '<': out.append("&lt;"); break; case '>': out.append("&gt;"); break; case '"': out.append("&quot;"); break; case '\'': out.append("&apos;"); break; default: out.append(c); }
        }
        return out.toString();
    }
}
