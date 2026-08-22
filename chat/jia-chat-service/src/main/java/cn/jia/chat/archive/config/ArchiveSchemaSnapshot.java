package cn.jia.chat.archive.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ArchiveSchemaSnapshot(Map<String, TableSnapshot> tables) {
    public ArchiveSchemaSnapshot {
        tables = Map.copyOf(new LinkedHashMap<>(tables));
    }

    public record TableSnapshot(
            String engine,
            String collation,
            Map<String, ColumnSnapshot> columns,
            Map<String, IndexSnapshot> indexes,
            Map<String, ForeignKeySnapshot> foreignKeys,
            Map<String, String> checks) {
        public TableSnapshot {
            columns = Map.copyOf(new LinkedHashMap<>(columns));
            indexes = Map.copyOf(new LinkedHashMap<>(indexes));
            foreignKeys = Map.copyOf(new LinkedHashMap<>(foreignKeys));
            checks = Map.copyOf(new LinkedHashMap<>(checks));
        }

        public TableSnapshot withColumns(Map<String, ColumnSnapshot> replacement) {
            return new TableSnapshot(engine, collation, replacement, indexes, foreignKeys, checks);
        }

        public TableSnapshot withIndexes(Map<String, IndexSnapshot> replacement) {
            return new TableSnapshot(engine, collation, columns, replacement, foreignKeys, checks);
        }
    }

    public record ColumnSnapshot(String dataType, String columnType, boolean nullable, String collation) {
        public ColumnSnapshot withCollation(String replacement) {
            return new ColumnSnapshot(dataType, columnType, nullable, replacement);
        }
    }

    public record IndexSnapshot(boolean unique, List<String> columns) {
        public IndexSnapshot {
            columns = List.copyOf(columns);
        }
    }

    public record ForeignKeySnapshot(
            List<String> columns,
            String referencedTable,
            List<String> referencedColumns,
            String updateRule,
            String deleteRule) {
        public ForeignKeySnapshot {
            columns = List.copyOf(columns);
            referencedColumns = List.copyOf(referencedColumns);
        }
    }
}
