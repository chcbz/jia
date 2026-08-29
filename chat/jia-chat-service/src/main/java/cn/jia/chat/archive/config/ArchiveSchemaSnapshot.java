package cn.jia.chat.archive.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ArchiveSchemaSnapshot(Map<String, TableSnapshot> tables) {
    public ArchiveSchemaSnapshot {
        tables = immutableLinkedMap(tables);
    }

    public record TableSnapshot(
            String engine,
            String collation,
            Map<String, ColumnSnapshot> columns,
            Map<String, IndexSnapshot> indexes,
            Map<String, ForeignKeySnapshot> foreignKeys,
            Map<String, CheckSnapshot> checks) {
        public TableSnapshot {
            columns = immutableLinkedMap(columns);
            indexes = immutableLinkedMap(indexes);
            foreignKeys = immutableLinkedMap(foreignKeys);
            checks = immutableLinkedMap(checks);
        }

        public TableSnapshot withColumns(Map<String, ColumnSnapshot> replacement) {
            return new TableSnapshot(engine, collation, replacement, indexes, foreignKeys, checks);
        }

        public TableSnapshot withIndexes(Map<String, IndexSnapshot> replacement) {
            return new TableSnapshot(engine, collation, columns, replacement, foreignKeys, checks);
        }

        public TableSnapshot withChecks(Map<String, CheckSnapshot> replacement) {
            return new TableSnapshot(engine, collation, columns, indexes, foreignKeys, replacement);
        }
    }

    public record ColumnSnapshot(
            int ordinal,
            String dataType,
            String columnType,
            boolean nullable,
            String collation,
            String defaultValue,
            String extra,
            String generationExpression) {
        public ColumnSnapshot withOrdinal(int replacement) {
            return new ColumnSnapshot(replacement, dataType, columnType, nullable, collation,
                    defaultValue, extra, generationExpression);
        }

        public ColumnSnapshot withCollation(String replacement) {
            return new ColumnSnapshot(ordinal, dataType, columnType, nullable, replacement,
                    defaultValue, extra, generationExpression);
        }

        public ColumnSnapshot withDefaultValue(String replacement) {
            return new ColumnSnapshot(ordinal, dataType, columnType, nullable, collation,
                    replacement, extra, generationExpression);
        }

        public ColumnSnapshot withExtra(String replacement) {
            return new ColumnSnapshot(ordinal, dataType, columnType, nullable, collation,
                    defaultValue, replacement, generationExpression);
        }

        public ColumnSnapshot withGenerationExpression(String replacement) {
            return new ColumnSnapshot(ordinal, dataType, columnType, nullable, collation,
                    defaultValue, extra, replacement);
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

    public record CheckSnapshot(String clause, boolean enforced) {
        public CheckSnapshot withEnforced(boolean replacement) {
            return new CheckSnapshot(clause, replacement);
        }
    }

    private static <K, V> Map<K, V> immutableLinkedMap(Map<K, V> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
