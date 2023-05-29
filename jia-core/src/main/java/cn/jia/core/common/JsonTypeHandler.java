package cn.jia.core.common;

import cn.jia.core.util.JsonUtil;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 继承自BaseTypeHandler<Object> 使用Object是为了让JsonUtil可以处理任意类型
 *
 * @author chc
 */
public class JsonTypeHandler extends BaseTypeHandler<Object> {

	@Override
	public void setNonNullParameter(PreparedStatement ps, int i, Object parameter, JdbcType jdbcType)
			throws SQLException {

		ps.setString(i, JsonUtil.toJson(parameter));
	}

	@Override
	public Object getNullableResult(ResultSet rs, String columnName) throws SQLException {

		return JsonUtil.fromJson(rs.getString(columnName), Object.class);
	}

	@Override
	public Object getNullableResult(ResultSet rs, int columnIndex) throws SQLException {

		return JsonUtil.fromJson(rs.getString(columnIndex), Object.class);
	}

	@Override
	public Object getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {

		return JsonUtil.fromJson(cs.getString(columnIndex), Object.class);
	}

}