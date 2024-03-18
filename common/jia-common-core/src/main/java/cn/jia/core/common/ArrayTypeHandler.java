package cn.jia.core.common;

import cn.jia.core.util.JsonUtil;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 继承自BaseTypeHandler<Object[]> 使用时传入的参数一定要是Object[]，例如 int[]是 Object, 不是Object[]，所以传入int[] 会报错的
 * @author chc
 */
public class ArrayTypeHandler extends BaseTypeHandler<Object[]> {

	@Override
	public void setNonNullParameter(PreparedStatement ps, int i, Object[] parameter, JdbcType jdbcType)
			throws SQLException {
		ps.setString(i, JsonUtil.toJson(parameter));
	}

	@Override
	public Object[] getNullableResult(ResultSet rs, String columnName) throws SQLException {
		return getArray(rs.getString(columnName));
	}

	@Override
	public Object[] getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
		return getArray(rs.getString(columnIndex));
	}

	@Override
	public Object[] getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
		return getArray(cs.getString(columnIndex));
	}

	private Object[] getArray(String array) {
		if (array == null) {
			return null;
		}
		try {
			return JsonUtil.getArray(array);
		} catch (Exception e) {
		}
		return null;
	}
}