package cn.jia.core.mybatis.typehandler;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

public class StringSetTypeHandler extends BaseTypeHandler<Set<String>> {

	@Override
	public void setNonNullParameter(PreparedStatement ps, int i, Set<String> parameter, JdbcType jdbcType)
			throws SQLException {
		ps.setString(i, String.join(",", parameter));
	}

	@Override
	public Set<String> getNullableResult(ResultSet rs, String columnName) throws SQLException {
		String str = rs.getString(columnName);
        if (rs.wasNull())
            return null;
		return new HashSet<String>(Arrays.asList(str.split(",")));
	}

	@Override
	public Set<String> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
		String str = rs.getString(columnIndex);
        if (rs.wasNull())
            return null;
        return new HashSet<String>(Arrays.asList(str.split(",")));
	}

	@Override
	public Set<String> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
		String str = cs.getString(columnIndex);
        if (cs.wasNull())
            return null;
        return new HashSet<String>(Arrays.asList(str.split(",")));
	}

}
