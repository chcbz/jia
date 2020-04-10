package cn.jia.isp.entity;

public class CmsColumnDTO extends CmsColumn {

    private String tableName;

    private String dbType;

    private Integer dbPrecision;

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getDbType() {
        if ("image".equals(this.getType())) {
            return "varchar";
        }
        return this.getType();
    }

    public void setDbType(String dbType) {
        this.dbType = dbType;
    }

    public Integer getDbPrecision() {
        if ("image".equals(this.getType())) {
            return 200;
        }
        return super.getPrecision();
    }

    public void setDbPrecision(Integer dbPrecision) {
        this.dbPrecision = dbPrecision;
    }
}