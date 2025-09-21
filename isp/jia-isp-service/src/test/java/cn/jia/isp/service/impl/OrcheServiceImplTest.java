package cn.jia.isp.service.impl;

import cn.jia.core.util.JsonUtil;
import cn.jia.isp.entity.OrcheEntity;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

class OrcheServiceImplTest {
    private final OrcheServiceImpl orcheService = new OrcheServiceImpl();

    @Test
    void executeOrche() {
        String orcheMetaStr = """
                {
                	"entityName": "areaChinaData",
                	"joins": [
                		{
                			"joinType": "union",
                			"entityName": "areaOverseaData",
                			"combineType": "and",
                			"operations": [
                				{
                					"operationType": "condition",
                					"left": {
                						"valueType": "VARIABLE",
                						"variableName": "areaOverseaData.region"
                					},
                					"right": {
                						"valueType": "VARIABLE",
                						"variableName": "areaChinaData.region"
                					},
                					"operator": "="
                				},
                				{
                					"operationType": "condition",
                					"left": {
                						"valueType": "VARIABLE",
                						"variableName": "areaOverseaData.repOffice"
                					},
                					"right": {
                						"valueType": "VARIABLE",
                						"variableName": "areaChinaData.repOffice"
                					},
                					"operator": "="
                				}
                			]
                		},
                		{
                			"joinType": "left",
                			"entityName": "stockData",
                			"combineType": "and",
                			"operations": [
                				{
                					"operationType": "condition",
                					"left": {
                						"valueType": "VARIABLE",
                						"variableName": "areaChinaData.region"
                					},
                					"right": {
                						"valueType": "VARIABLE",
                						"variableName": "stockData.region"
                					},
                					"operator": "="
                				},
                				{
                					"operationType": "condition",
                					"left": {
                						"valueType": "VARIABLE",
                						"variableName": "areaChinaData.repOffice"
                					},
                					"right": {
                						"valueType": "VARIABLE",
                						"variableName": "stockData.repOffice"
                					},
                					"operator": "="
                				}
                			]
                		},
                		{
                			"joinType": "left",
                			"entityName": "completeData",
                			"combineType": "and",
                			"operations": [
                				{
                					"operationType": "condition",
                					"left": {
                						"valueType": "VARIABLE",
                						"variableName": "areaChinaData.region"
                					},
                					"right": {
                						"valueType": "VARIABLE",
                						"variableName": "completeData.region"
                					},
                					"operator": "="
                				},
                				{
                					"operationType": "condition",
                					"left": {
                						"valueType": "VARIABLE",
                						"variableName": "areaChinaData.repOffice"
                					},
                					"right": {
                						"valueType": "VARIABLE",
                						"variableName": "completeData.repOffice"
                					},
                					"operator": "="
                				}
                			]
                		}
                	],
                	"groupBys": [
                		"areaChinaData.region",
                		"areaChinaData.repOffice"
                	],
                	"resultSets": [
                		{
                			"alias": "region",
                			"valueType": "VARIABLE",
                			"variableName": "areaChinaData.region"
                		},
                		{
                			"alias": "repOffice",
                			"valueType": "VARIABLE",
                			"variableName": "areaChinaData.repOffice"
                		},
                		{
                			"alias": "stock",
                			"valueType": "EXPRESS",
                			"variableName": "sum(stockData.num)"
                		},
                		{
                			"alias": "complete",
                			"valueType": "EXPRESS",
                			"variableName": "sum(completeData.num)"
                		},
                		{
                			"alias": "last",
                			"valueType": "EXPRESS",
                			"variableName": "sum(stockData.num - completeData.num)"
                		}
                	]
                }
                """;
        String areaChinaDataStr = """
                [{
                    "region": "1079",
                    "repOffice": "5017"
                }, {
                    "region": "1079",
                    "repOffice": "5018"
                }]
                """;
        String areaOverseaDataStr = """
                [{
                    "region": "1080",
                    "repOffice": "5019"
                }]
                """;
        String stockDataStr = """
                [{
                    "stockId": "1",
                    "region": "1079",
                    "repOffice": "5017",
                    "num": "100"
                }, {
                    "stockId": "2",
                    "region": "1079",
                    "repOffice": "5018",
                    "num": "100"
                }, {
                    "stockId": "3",
                    "region": "1080",
                    "repOffice": "5019",
                    "num": "100"
                }]
                """;
        String completeDataStr = """
                [{
                    "completeId": "1",
                    "region": "1079",
                    "repOffice": "5017",
                    "num": "11"
                }, {
                    "completeId": "2",
                    "region": "1079",
                    "repOffice": "5018",
                    "num": "12"
                }, {
                    "completeId": "3",
                    "region": "1080",
                    "repOffice": "5019",
                    "num": "13"
                }]
                """;
        OrcheEntity orcheMeta = Objects.requireNonNull(JsonUtil.fromJson(orcheMetaStr, OrcheEntity.class));
        Map<String, List<Map<String, Object>>> data = new HashMap<>();
        data.put("areaChinaData", JsonUtil.jsonToListMap(areaChinaDataStr));
        data.put("areaOverseaData", JsonUtil.jsonToListMap(areaOverseaDataStr));
        data.put("stockData", JsonUtil.jsonToListMap(stockDataStr));
        data.put("completeData", JsonUtil.jsonToListMap(completeDataStr));
        List<Map<String, Object>> result = orcheService.executeOrche(data, orcheMeta);
        System.out.println( result);
        Assertions.assertEquals(3, result.size());
        Assertions.assertEquals("1079", result.get(0).get("region"));
        Assertions.assertEquals("5017", result.get(0).get("repOffice"));
        Assertions.assertEquals(100.0, result.get(0).get("stock"));
        Assertions.assertEquals(11.0, result.get(0).get("complete"));
        Assertions.assertEquals(89.0, result.get(0).get("last"));
        Assertions.assertEquals("1079", result.get(1).get("region"));
        Assertions.assertEquals("5018", result.get(1).get("repOffice"));
        Assertions.assertEquals(100.0, result.get(1).get("stock"));
        Assertions.assertEquals(12.0, result.get(1).get("complete"));
        Assertions.assertEquals(88.0, result.get(1).get("last"));
        Assertions.assertEquals("1080", result.get(2).get("region"));
        Assertions.assertEquals("5019", result.get(2).get("repOffice"));
        Assertions.assertEquals(100.0, result.get(2).get("stock"));
        Assertions.assertEquals(13.0, result.get(2).get("complete"));
        Assertions.assertEquals(87.0, result.get(2).get("last"));
    }
}