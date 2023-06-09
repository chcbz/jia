package cn.jia.core.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.dom4j.*;
import org.dom4j.io.OutputFormat;
import org.dom4j.io.XMLWriter;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.util.*;

/**
 * xml转map，map转xml 带属性 http://happyqing.iteye.com/blog/2316275
 * 
 * @author happyqing
 * @since 2016.8.8
 */
@Slf4j
@SuppressWarnings({ "rawtypes", "unchecked" })
public class XmlUtil {
	public static void main(String[] args) throws DocumentException, IOException {
		String textFromFile = FileUtils
				.readFileToString(new File("D:/workspace/workspace_3.7/xml2map/src/xml2json/sample.xml"), "UTF-8");
		Map<String, Object> map = xml2map(textFromFile, false);
		// long begin = System.currentTimeMillis();
		// for(int i=0; i<1000; i++){
		// map = (Map<String, Object>) xml2mapWithAttr(doc.getRootElement());
		// }
		// log.info("耗时:"+(System.currentTimeMillis()-begin));
		JSONObject json = new JSONObject(map);
		// 格式化输出
		log.info(json.toString(1));

		Document doc = map2xml(map, "root");
		// Document doc = map2xml(map); //map中含有根节点的键
		log.info(formatXml(doc));
	}

	/**
	 * xml转map 不带属性
	 * 
	 * @param xmlStr
	 * @param needRootKey
	 *            是否需要在返回的map里加根节点键
	 * @return
	 * @throws DocumentException
	 */
	public static Map xml2map(String xmlStr, boolean needRootKey) throws DocumentException {
		Document doc = DocumentHelper.parseText(xmlStr);
		Element root = doc.getRootElement();
		Map<String, Object> map = (Map<String, Object>) xml2map(root);
		if (root.elements().size() == 0 && root.attributes().size() == 0) {
			return map;
		}
		if (needRootKey) {
			// 在返回的map里加根节点键（如果需要）
			Map<String, Object> rootMap = new HashMap<>(16);
			rootMap.put(root.getName(), map);
			return rootMap;
		}
		return map;
	}

	/**
	 * xml转map 带属性
	 * 
	 * @param xmlStr
	 * @param needRootKey
	 *            是否需要在返回的map里加根节点键
	 * @return
	 * @throws DocumentException
	 */
	public static Map xml2mapWithAttr(String xmlStr, boolean needRootKey) throws DocumentException {
		Document doc = DocumentHelper.parseText(xmlStr);
		Element root = doc.getRootElement();
		Map<String, Object> map = (Map<String, Object>) xml2mapWithAttr(root);
		if (root.elements().size() == 0 && root.attributes().size() == 0) {
			// 根节点只有一个文本内容
			return map;
		}
		if (needRootKey) {
			// 在返回的map里加根节点键（如果需要）
			Map<String, Object> rootMap = new HashMap<>(1);
			rootMap.put(root.getName(), map);
			return rootMap;
		}
		return map;
	}

	/**
	 * xml转map 不带属性
	 * 
	 * @param e
	 * @return
	 */
	private static Map xml2map(Element e) {
		Map map = new LinkedHashMap();
		List list = e.elements();
		if (list.size() > 0) {
			for (Object o : list) {
				Element iter = (Element) o;
				List mapList = new ArrayList();

				if (iter.elements().size() > 0) {
					Map m = xml2map(iter);
					if (map.get(iter.getName()) != null) {
						Object obj = map.get(iter.getName());
						if (!(obj instanceof List)) {
							mapList = new ArrayList();
							mapList.add(obj);
							mapList.add(m);
						}
						if (obj instanceof List) {
							mapList = (List) obj;
							mapList.add(m);
						}
						map.put(iter.getName(), mapList);
					} else {
						map.put(iter.getName(), m);
					}
				} else {
					if (map.get(iter.getName()) != null) {
						Object obj = map.get(iter.getName());
						if (!(obj instanceof List)) {
							mapList = new ArrayList();
							mapList.add(obj);
							mapList.add(iter.getText());
						}
						if (obj instanceof List) {
							mapList = (List) obj;
							mapList.add(iter.getText());
						}
						map.put(iter.getName(), mapList);
					} else {
						map.put(iter.getName(), iter.getText());
					}
				}
			}
		} else {
			map.put(e.getName(), e.getText());
		}
		return map;
	}

	/**
	 * xml转map 带属性
	 * 
	 * @param element
	 * @return
	 */
	private static Map xml2mapWithAttr(Element element) {
		Map<String, Object> map = new LinkedHashMap<>();

		List<Element> list = element.elements();
		// 当前节点的所有属性的list
		List<Attribute> listAttr0 = element.attributes();
		for (Attribute attr : listAttr0) {
			map.put("@" + attr.getName(), attr.getValue());
		}
		if (list.size() > 0) {

			for (Element iter : list) {
				List mapList = new ArrayList();

				if (iter.elements().size() > 0) {
					Map m = xml2mapWithAttr(iter);
					if (map.get(iter.getName()) != null) {
						Object obj = map.get(iter.getName());
						if (!(obj instanceof List)) {
							mapList = new ArrayList();
							mapList.add(obj);
							mapList.add(m);
						}
						if (obj instanceof List) {
							mapList = (List) obj;
							mapList.add(m);
						}
						map.put(iter.getName(), mapList);
					} else {
						map.put(iter.getName(), m);
					}
				} else {
					// 当前节点的所有属性的list
					List<Attribute> listAttr = iter.attributes();
					Map<String, Object> attrMap = null;
					boolean hasAttributes = false;
					if (listAttr.size() > 0) {
						hasAttributes = true;
						attrMap = new LinkedHashMap<>();
						for (Attribute attr : listAttr) {
							attrMap.put("@" + attr.getName(), attr.getValue());
						}
					}

					if (map.get(iter.getName()) != null) {
						Object obj = map.get(iter.getName());
						if (!(obj instanceof List)) {
							mapList = new ArrayList();
							mapList.add(obj);
							// mapList.add(iter.getText());
							if (hasAttributes) {
								attrMap.put("#text", iter.getText());
								mapList.add(attrMap);
							} else {
								mapList.add(iter.getText());
							}
						}
						if (obj instanceof List) {
							mapList = (List) obj;
							// mapList.add(iter.getText());
							if (hasAttributes) {
								attrMap.put("#text", iter.getText());
								mapList.add(attrMap);
							} else {
								mapList.add(iter.getText());
							}
						}
						map.put(iter.getName(), mapList);
					} else {
						// map.put(iter.getName(), iter.getText());
						if (hasAttributes) {
							attrMap.put("#text", iter.getText());
							map.put(iter.getName(), attrMap);
						} else {
							map.put(iter.getName(), iter.getText());
						}
					}
				}
			}
		} else {
			// 根节点的
			if (listAttr0.size() > 0) {
				map.put("#text", element.getText());
			} else {
				map.put(element.getName(), element.getText());
			}
		}
		return map;
	}

	/**
	 * map转xml map中没有根节点的键
	 * 
	 * @param map
	 * @param rootName
	 * @throws DocumentException
	 * @throws IOException
	 */
	public static Document map2xml(Map<String, Object> map, String rootName) throws DocumentException, IOException {
		Document doc = DocumentHelper.createDocument();
		Element root = DocumentHelper.createElement(rootName);
		doc.add(root);
		map2xml(map, root);
		// log.info(doc.asXML());
		// log.info(formatXml(doc));
		return doc;
	}

	/**
	 * map转xml map中含有根节点的键
	 * 
	 * @param map
	 * @throws DocumentException
	 * @throws IOException
	 */
	public static Document map2xml(Map<String, Object> map) throws DocumentException, IOException {
		Iterator<Map.Entry<String, Object>> entries = map.entrySet().iterator();
		// 获取第一个键创建根节点
		if (entries.hasNext()) {
			Map.Entry<String, Object> entry = entries.next();
			Document doc = DocumentHelper.createDocument();
			Element root = DocumentHelper.createElement(entry.getKey());
			doc.add(root);
			map2xml((Map) entry.getValue(), root);
			// log.info(doc.asXML());
			// log.info(formatXml(doc));
			return doc;
		}
		return null;
	}

	/**
	 * map转xml
	 * 
	 * @param map
	 * @param body
	 *            xml元素
	 * @return
	 */
	private static Element map2xml(Map<String, Object> map, Element body) {
		for (Map.Entry<String, Object> entry : map.entrySet()) {
			String key = entry.getKey();
			Object value = entry.getValue();
			// 属性
			if (key.startsWith("@")) {
				body.addAttribute(key.substring(1), value.toString());
			}
			// 有属性时的文本
			else if ("#text".equals(key)) {
				body.setText(value.toString());
			} else {
				if (value instanceof List) {
					List list = (List) value;
					Object obj;
					for (Object o : list) {
						obj = o;
						// list里是map或String，不会存在list里直接是list的，
						if (obj instanceof Map) {
							Element subElement = body.addElement(key);
							map2xml((Map) o, subElement);
						} else {
							body.addElement(key).setText((String) o);
						}
					}
				} else if (value instanceof Map) {
					Element subElement = body.addElement(key);
					map2xml((Map) value, subElement);
				} else {
					body.addElement(key).setText(value.toString());
				}
			}
			// log.info("Key = " + entry.getKey() + ", Value = " +
			// entry.getValue());
		}
		return body;
	}

	/**
	 * 格式化输出xml
	 * 
	 * @param xmlStr
	 * @return
	 * @throws DocumentException
	 * @throws IOException
	 */
	public static String formatXml(String xmlStr) throws DocumentException, IOException {
		Document document = DocumentHelper.parseText(xmlStr);
		return formatXml(document);
	}

	/**
	 * 格式化输出xml
	 * 
	 * @param document
	 * @return
	 * @throws DocumentException
	 * @throws IOException
	 */
	public static String formatXml(Document document) throws DocumentException, IOException {
		// 格式化输出格式
		OutputFormat format = OutputFormat.createPrettyPrint();
		// format.setEncoding("UTF-8");
		StringWriter writer = new StringWriter();
		// 格式化输出流
		XMLWriter xmlWriter = new XMLWriter(writer, format);
		// 将document写入到输出流
		xmlWriter.write(document);
		xmlWriter.close();
		return writer.toString();
	}

}
