package com.zam.logviewer.renderers;

import com.google.common.collect.ImmutableList;

import com.google.common.collect.ImmutableMap;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathExpressionException;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

class FIXPreProcessor
{
    private static final Map<String, String> EMPTY_MAP = ImmutableMap.of();
    private static final String FIELDS_XPATH = "/fix/fields/field";
    private static final String COMPONENTS_XPATH = "/fix/components/component";
    private static final String MESSAGES_XPATH = "/fix/messages/message";
    private static final String HEADER_XPATH = "/fix/header";
    private static final String TRAILER_XPATH = "/fix/trailer";
    private static final ImmutableList<String> RELEVANT_NODE_TYPES = ImmutableList.of("field", "group", "component");
    public static final String NUMINGROUP = "NUMINGROUP";
    private final Map<String, FixFieldNode> messageToFixTree = new HashMap<>();
    private final Map<Integer, String> fieldsIdToName = new HashMap<>();
    private final Map<Integer, String> fieldsIdToType = new HashMap<>();
    private final Map<String, Integer> fieldsNameToId = new HashMap<>();
    private final Map<Integer, Map<String, String>> enums = new HashMap<>();
    private final List<Document> documents;

    FIXPreProcessor(final InputStream... inputStreams) throws
                                                       IOException,
                                                       SAXException,
                                                       ParserConfigurationException,
                                                       XPathExpressionException
    {
        documents = new ArrayList<>();
        for (final InputStream inputStream : inputStreams)
        {
            documents.add(XmlFunctions.getDocument(inputStream));
        }
        preProcessFields(documents);
    }

    FixFieldNode getFixTreeRoot(final String messageTypeKey)
    {
        return messageToFixTree.get(messageTypeKey);
    }

    Optional<String> getEnumKeyRepr(final int fieldKey, final String enumValue)
    {
        return Optional.ofNullable(enums.getOrDefault(fieldKey, EMPTY_MAP).get(enumValue));
    }

    Optional<String> getFieldKeyRepr(final int fieldKey)
    {
        return Optional.ofNullable(fieldsIdToName.get(fieldKey));
    }

    private void preProcessFields(final List<Document> documents)
            throws
            XPathExpressionException
    {
        final HashMap<String, NodeList> componentsByName = new HashMap<>();
        for (final Document document : documents)
        {
            final NodeList components = XmlFunctions.getNodeList(document, COMPONENTS_XPATH);
            populateComponents(components, componentsByName);
            final NodeList fields = XmlFunctions.getNodeList(document, FIELDS_XPATH);
            populateFields(fields, fieldsIdToName, fieldsNameToId, fieldsIdToType, enums);
        }

        final List<Node> headerAndTrailer = new ArrayList<>();
        final List<Node> allMessages = new ArrayList<>();
        for (final Document document : this.documents)
        {
            final NodeList messages = XmlFunctions.getNodeList(document, MESSAGES_XPATH);
            final Node header = XmlFunctions.getNode(document, HEADER_XPATH);
            final Node trailer = XmlFunctions.getNode(document, TRAILER_XPATH);
            if (header != null && header.hasChildNodes())
            {
                XmlFunctions.forEach(header.getChildNodes(), headerAndTrailer::add);
            }
            if (trailer != null && trailer.hasChildNodes())
            {
                XmlFunctions.forEach(trailer.getChildNodes(), headerAndTrailer::add);
            }
            XmlFunctions.forEach(messages, allMessages::add);
        }

        for (final Node message : allMessages)
        {
            addHeaderTrailerFields(headerAndTrailer, message);
            flattenComponentStructure(message, componentsByName);
            populateFixTree(messageToFixTree, message, fieldsNameToId, fieldsIdToType);
        }
    }

    private void addHeaderTrailerFields(final List<Node> headerAndTrailer, final Node message)
    {
        headerAndTrailer.forEach(newChild ->
                                 {
                                     final Node toInsert = newChild.cloneNode(true);
                                     message.getOwnerDocument().adoptNode(toInsert);
                                     message.appendChild(toInsert);
                                 });
    }

    private void populateFixTree(final Map<String, FixFieldNode> messageTypeKeyToFixTree,
                                 final Node message,
                                 final Map<String, Integer> fieldsNameToId,
                                 final Map<Integer, String> fieldsIdToType)
    {
        final String messageName = getField(message, "name");
        final String messageType = getField(message, "msgtype");
        final FixFieldNode messageRoot = new FixFieldNode(-1, messageName);
        messageTypeKeyToFixTree.put(messageType, messageRoot);
        insertAllChildren(message.getChildNodes(), messageRoot, fieldsNameToId, fieldsIdToType);
    }

    private static void insertAllChildren(final NodeList nodes,
                                   final FixFieldNode messageRoot,
                                   final Map<String, Integer> fieldsNameToId,
                                   final Map<Integer, String> fieldsIdToType)
    {
        XmlFunctions.forEach(nodes, node ->
        {
            final String name = getField(node, "name");
            final int id = fieldsNameToId.get(name);
            final FixFieldNode field = new FixFieldNode(id, name);
            if (fieldsIdToType.get(id).equals(NUMINGROUP))
            {
                insertAllChildren(node.getChildNodes(), field, fieldsNameToId, fieldsIdToType);
            }
            messageRoot.children.put(id, field);
        });
    }

    private static void flattenComponentStructure(final Node message, final Map<String, NodeList> components)
    {
        int replacementsDone;
        do
        {
            replacementsDone = flattenComponents(message, components);
        }
        while (replacementsDone > 0);
    }

    private static int flattenComponents(final Node root, final Map<String, NodeList> components)
    {
        final AtomicInteger replacementsDone = new AtomicInteger(0);
        XmlFunctions.forEach(root.getChildNodes(), field ->
        {
            if (!field.hasAttributes())
            {
                return;
            }

            final String type = field.getNodeName();
            if ("group".equals(type))
            {
                replacementsDone.getAndAdd(flattenComponents(field, components));
            }
            else if ("component".equals(type))
            {
                final NodeList replacements = components.get(getField(field, "name"));
                XmlFunctions.forEach(replacements, toInsert ->
                {
                    if (!RELEVANT_NODE_TYPES.contains(toInsert.getNodeName()))
                    {
                        return;
                    }

                    final Node newChild = toInsert.cloneNode(true);
                    root.getOwnerDocument().adoptNode(newChild);
                    root.insertBefore(newChild, field);
                    replacementsDone.incrementAndGet();
                });
                root.removeChild(field);
            }
        });
        return replacementsDone.get();
    }

    private static void populateComponents(final NodeList components,
                                           final Map<String, NodeList> componentsByName)
    {
        XmlFunctions.forEach(components, node ->
        {
            if (node instanceof org.w3c.dom.Element)
            {
                componentsByName.put(getField(node, "name"), node.getChildNodes());
            }
        });
    }

    private static void populateFields(final NodeList nodeList,
                                       final Map<Integer, String> fieldsIdToName,
                                       final Map<String, Integer> fieldsNameToId,
                                       final Map<Integer, String> fieldsIdToType,
                                       final Map<Integer, Map<String, String>> enums)
    {
        XmlFunctions.forEach(nodeList, node ->
                populateFields(fieldsIdToName, fieldsNameToId, fieldsIdToType, enums, node));
    }

    private static void populateFields(final Map<Integer, String> fieldsIdToName,
                                       final Map<String, Integer> fieldsNameToId,
                                       final Map<Integer, String> fieldsIdToType,
                                       final Map<Integer, Map<String, String>> enums,
                                       final Node node)
    {
        final String name = getField(node, "name");
        final int number = Integer.parseInt(getField(node, "number"));
        final String type = getField(node, "type");
        fieldsIdToName.put(number, name);
        fieldsNameToId.put(name, number);
        fieldsIdToType.put(number, type);
        if (node.hasChildNodes())
        {
            XmlFunctions.forEach(node.getChildNodes(), childNode ->
            {
                final String enumName = getField(childNode, "description");
                final String enumValue = getField(childNode, "enum");
                final Map<String, String> enumMap = enums.computeIfAbsent(number, HashMap::new);
                enumMap.put(enumValue, enumName);
            });
        }
    }

    private static String getField(final Node node, final String field)
    {
        return node.getAttributes().getNamedItem(field).getNodeValue();
    }

    public static final class FixFieldNode
    {

        private final String fieldName;
        private final int key;
        private final Map<Integer, FixFieldNode> children = new HashMap<>();

        FixFieldNode(final int key, final String fieldName)
        {
            this.key = key;
            this.fieldName = fieldName;
        }

        boolean hasChildren()
        {
            return !children.isEmpty();
        }

        public String getFieldName()
        {
            return fieldName;
        }

        public int getKey()
        {
            return key;
        }

        public Map<Integer, FixFieldNode> getChildren()
        {
            return children;
        }

        @Override
        public String toString()
        {
            return "FixFieldNode{" +
                   "fieldName='" + fieldName + '\'' +
                   ", key=" + key +
                   '}';
        }
    }
}
