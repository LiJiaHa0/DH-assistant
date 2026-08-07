package cn.john.dh.assistant.rag.strategy.service;

import cn.john.dh.assistant.rag.domain.entity.KnowledgeDocument;
import cn.john.dh.assistant.rag.domain.entity.TableMeta;
import cn.john.dh.assistant.rag.domain.enums.FileType;
import cn.john.dh.assistant.rag.domain.enums.KnowledgeBaseType;
import cn.john.dh.assistant.rag.mapper.TableMetaMapper;
import cn.john.dh.assistant.rag.strategy.FileProcessService;
import cn.john.dh.assistant.utils.BusinessExceptionUtils;
import com.alibaba.druid.sql.transform.TableMapping;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.formula.functions.T;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @Author John
 * @Date 2026-08-04 23:10
 */
@Service
@Slf4j
public class ExcelProcessServiceImpl implements FileProcessService {

    // 表名前缀
    private static final String TABLE_PREFIX = "dh_data_query_";

    @Autowired
    private TableMetaMapper tableMetaMapper;

    /**
     * 处理excel文档
     * @param document
     * @param inputStream
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String processDocument(KnowledgeDocument document, InputStream inputStream) {
        String documentTitle = document.getDocTitle();
        String originalTableName = document.getTableName();
        Long versionId = document.getCurrentVersionId();
        BusinessExceptionUtils.throwBusinessException(Objects.isNull(versionId), "文档当前版本ID不能为空");
        log.info("开始处理Excel文件: {}, versionId={}", documentTitle, versionId);
        try {
            // 解析Excel文件
            List<List<String>> excelData = parseExcel(inputStream);
            if (excelData.isEmpty() || excelData.size() < 2) {
                throw new IllegalArgumentException("Excel文件为空或只有表头，没有数据行");
            }
            //获取表头
            List<String> headers = excelData.get(0);
            if (headers.isEmpty()) {
                throw new IllegalArgumentException("Excel表头为空");
            }
            String tableName = generatePhysicalTableName(originalTableName);
            log.info("Excel文件表头: {}", headers);
            //生成列信息
            List<ColumnInfo> columns = generateColumnInfo(headers);
            //判断表是否已存在
            boolean tableExists = tableMetaMapper.checkTableExists(tableName) > 0;
            if (tableExists) {
                ifTableExists(tableName, excelData, columns, document);
            }else{
                ifTableNotExists(tableName, excelData, columns, document);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (Exception ignored) {
                    // 忽略关闭异常
                }
            }
        }
        return null;
    }

    /**
     * 如果表存在相关逻辑
     * @param tableName
     * @param excelData
     * @param columns
     * @param document
     */
    private void ifTableExists(String tableName, List<List<String>> excelData, List<ColumnInfo> columns, KnowledgeDocument document) {
        //已存在：校验表结构必须与之前完全一致，否则禁止上传
        TableMeta existingMeta = tableMetaMapper.selectOne(
                new LambdaQueryWrapper<TableMeta>()
                        .eq(TableMeta::getTableName, tableName));
        BusinessExceptionUtils.throwBusinessException(Objects.isNull(existingMeta),"表 " + tableName + " 的元数据不存在");
        // 解析已存在的表结构
        List<ColumnInfo> existingColumns = parseColumnInfo(existingMeta.getColumnsInfo());
        BusinessExceptionUtils.throwBusinessException(!isSchemaCompatible(existingColumns, columns), "Excel 表结构与已有表 " + tableName + " 不一致，禁止上传。请保持表头、列名、顺序及类型完全一致。");
        log.info("表 {} 已存在且结构一致，执行数据替换", tableName);
        // 删除现有表数据
        tableMetaMapper.physicalDeleteByTableName(tableName);
        log.info("表 {} 旧数据已清空", tableName);
        // 截取除表头以外的数据行
        List<List<String>> dataRows = excelData.subList(1, excelData.size());
        int insertedCount = insertData(tableName, columns, dataRows);
        log.info("表 {} 数据替换完成，新数据 {} 行", tableName, insertedCount);
        //更新元数据中的版本绑定为当前版本
        existingMeta.setVersionId(document.getCurrentVersionId());
        existingMeta.setDescription(document.getDescription() != null ? document.getDescription() : "从Excel导入: " + document.getDocTitle());
        existingMeta.setUpdatedAt(LocalDateTime.now());
        boolean updateResult = tableMetaMapper.updateById(existingMeta) > 0;
        BusinessExceptionUtils.throwBusinessException(!updateResult, "表元数据更新失败");
    }

    /**
     * 如果表不存在相关逻辑
     * @param tableName
     * @param excelData
     * @param columns
     * @param document
     */
    private void ifTableNotExists(String tableName, List<List<String>> excelData, List<ColumnInfo> columns, KnowledgeDocument document) {
        //表不存在：新建表并导数据
        String createTableSql = generateCreateTableSql(tableName, document.getDescription(), columns);
        log.info("生成建表SQL: {}", createTableSql);
        tableMetaMapper.executeCreateTable(createTableSql);
        log.info("表 {} 创建成功", tableName);
        List<List<String>> dataRows = excelData.subList(1, excelData.size());
        int insertedCount = insertData(tableName, columns, dataRows);
        log.info("插入数据 {} 行", insertedCount);
        TableMeta tableMeta = new TableMeta();
        tableMeta.setTableName(tableName);
        tableMeta.setDescription(document.getDescription() != null ? document.getDescription() : "从Excel导入: " + document.getDocTitle());
        tableMeta.setCreateSql(createTableSql);
        tableMeta.setColumnsInfo(JSON.toJSONString(columns));
        tableMeta.setVersionId(document.getCurrentVersionId());
        tableMeta.setCreatedAt(LocalDateTime.now());
        tableMeta.setUpdatedAt(LocalDateTime.now());
        int result = tableMetaMapper.insert(tableMeta);
        Assert.isTrue(result == 1, "表元数据保存失败");
        log.info("表元数据保存成功, ID: {}", tableMeta.getId());

    }

    /**
     * 生成建表SQL
     */
    private String generateCreateTableSql(String tableName, String description, List<ColumnInfo> columns) {
        StringBuilder sql = new StringBuilder();
        sql.append("CREATE TABLE IF NOT EXISTS `").append(tableName).append("` (\n");

        // 添加自增主键
        sql.append("  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',\n");

        // 添加Excel列
        for (ColumnInfo column : columns) {
            sql.append("  `").append(column.getColumnName()).append("` ")
                    .append(column.getDataType())
                    .append(" DEFAULT NULL COMMENT '")
                    .append(escapeSqlComment(column.getOriginalHeader()))
                    .append("',\n");
        }

        // 添加创建时间和更新时间
        sql.append("  `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',\n");
        sql.append("  `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',\n");

        // 设置主键
        sql.append("  PRIMARY KEY (`id`)\n");
        sql.append(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='" + description + "'");

        return sql.toString();
    }

    /**
     * 插入数据
     * @param tableName
     * @param columns
     * @param dataRows
     * @return
     */
    private int insertData(String tableName, List<ColumnInfo> columns, List<List<String>> dataRows) {
        int batchSize = 500; // 每批插入500条
        int totalInserted = 0;
        for (int i = 0; i < dataRows.size(); i += batchSize) {
            List<List<String>> batch = dataRows.subList(i, Math.min(i + batchSize, dataRows.size()));
            String insertSql = generateBatchInsertSql(tableName, columns, batch);
            // 使用 JdbcTemplate 执行，绕过 MyBatis-Plus 的BlockAttackInnerInterceptor拦截器
            tableMetaMapper.executeInsert(insertSql);
            totalInserted += batch.size();
        }
        return totalInserted;
    }

    /**
     * 生成批量插入SQL
     */
    private String generateBatchInsertSql(String tableName, List<ColumnInfo> columns, List<List<String>> rows) {
        StringBuilder sql = new StringBuilder();
        sql.append("INSERT INTO `").append(tableName).append("` (");
        // 列名
        String columnNames = columns.stream()
                .map(c -> "`" + c.getColumnName() + "`")
                .collect(Collectors.joining(", "));
        sql.append(columnNames).append(") VALUES ");
        // 值
        for (int i = 0; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("(");
            for (int j = 0; j < columns.size(); j++) {
                if (j > 0) {
                    sql.append(", ");
                }
                String value = j < row.size() ? row.get(j) : "";
                sql.append(escapeSqlValue(value));
            }
            sql.append(")");
        }
        return sql.toString();
    }

    /**
     * 转义SQL值
     */
    private String escapeSqlValue(String value) {
        if (value == null || value.isEmpty()) {
            return "NULL";
        }
        // 转义单引号
        String escaped = value.replace("'", "''");
        // 处理换行符和制表符
        escaped = escaped.replace("\\", "\\\\");
        escaped = escaped.replace("\n", "\\n");
        escaped = escaped.replace("\r", "\\r");
        escaped = escaped.replace("\t", "\\t");
        return "'" + escaped + "'";
    }

    /**
     * 转义SQL注释中的特殊字符
     */
    private String escapeSqlComment(String comment) {
        if (comment == null) {
            return "";
        }
        return comment.replace("'", "\\'").replace("\\", "\\\\");
    }

    /**
     * 判断两次上传的表结构是否一致
     * <p>
     * 要求：列数量、列名、数据类型、顺序完全一致
     */
    private boolean isSchemaCompatible(List<ColumnInfo> existingColumns, List<ColumnInfo> newColumns) {
        if (existingColumns == null || newColumns == null) {
            return existingColumns == newColumns;
        }
        if (existingColumns.size() != newColumns.size()) {
            return false;
        }
        for (int i = 0; i < existingColumns.size(); i++) {
            ColumnInfo a = existingColumns.get(i);
            ColumnInfo b = newColumns.get(i);
            if (a == null || b == null) {
                return false;
            }
            if (!Objects.equals(a.getColumnName(), b.getColumnName())) {
                return false;
            }
            if (!Objects.equals(a.getDataType(), b.getDataType())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 转换列信息
     * @param columnsInfoJson
     * @return
     */
    private List<ColumnInfo> parseColumnInfo(String columnsInfoJson) {
        if (columnsInfoJson == null || columnsInfoJson.isBlank()) {
            return Collections.emptyList();
        }
        return JSON.parseArray(columnsInfoJson, ColumnInfo.class);
    }

    /**
     * 生成列信息
     * @param headers
     * @return
     */
    private List<ColumnInfo> generateColumnInfo(List<String> headers) {
        List<ColumnInfo> columns = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();
        for (int i = 0; i < headers.size(); i++) {
            String header = headers.get(i);
            String columnName = sanitizeColumnName(header);
            String originalName = columnName;
            int suffix = 1;
            // 处理重复的列名
            while (usedNames.contains(columnName)) {
                columnName = originalName + "_" + suffix++;
            }
            usedNames.add(columnName);

            ColumnInfo column = new ColumnInfo();
            column.setIndex(i);
            column.setOriginalHeader(header);
            column.setColumnName(columnName);
            // 使用TEXT类型，数据存储在页外，行内仅保留指针，避免列数过多时超出MySQL行大小限制65535字节
            column.setDataType("TEXT");
            columns.add(column);
        }

        return columns;
    }

    /**
     * 清理列名中的非法字符
     * @param name
     * @return
     */
    private String sanitizeColumnName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "col";
        }
        // 转换为小写
        String sanitized = name.toLowerCase().trim();
        // 替换非法字符为下划线
        sanitized = sanitized.replaceAll("[^a-zA-Z0-9_]", "_");
        // 确保以字母开头
        if (!sanitized.matches("^[a-zA-Z].*")) {
            sanitized = "col_" + sanitized;
        }
        // 限制长度（MySQL列名最大64字符）
        if (sanitized.length() > 60) {
            sanitized = sanitized.substring(0, 60);
        }
        // 去掉连续和末尾的下划线
        sanitized = sanitized.replaceAll("_+", "_");
        sanitized = sanitized.replaceAll("_+$", "");

        return sanitized;

    }

    /**
     * 生成物理表名
     * @param originalFilename
     * @return
     */
    private String generatePhysicalTableName(String originalFilename) {
        String baseName = originalFilename;
        // 去掉扩展名
        int dotIndex = baseName.lastIndexOf('.');
        if (dotIndex > 0) {
            baseName = baseName.substring(0, dotIndex);
        }
        // 清理非法字符
        baseName = sanitizeTableName(baseName);
        // 限制 baseName 长度，确保加上前缀后不超过 MySQL 表名上限 64
        int maxBaseLength = 64 - TABLE_PREFIX.length();
        if (baseName.length() > maxBaseLength) {
            baseName = baseName.substring(0, maxBaseLength);
        }
        baseName = baseName.replaceAll("_+$", "");
        if (baseName.isEmpty()) {
            baseName = "table";
        }
        return TABLE_PREFIX + baseName;
    }

    /**
     * 清理表名中的非法字符
     * @param baseName
     * @return
     */
    private String sanitizeTableName(String baseName) {
        if (baseName == null || baseName.trim().isEmpty()) {
            return "table_" + System.currentTimeMillis();
        }
        // 转换为小写
        String sanitized = baseName.toLowerCase();
        // 替换非法字符为下划线
        sanitized = sanitized.replaceAll("[^a-zA-Z0-9_]", "_");
        // 确保以字母或下划线开头
        if (!sanitized.matches("^[a-zA-Z_].*")) {
            sanitized = "t_" + sanitized;
        }
        // 限制长度（MySQL表名最大64字符）
        if (sanitized.length() > 60) {
            sanitized = sanitized.substring(0, 60);
        }
        // 去掉末尾的下划线
        sanitized = sanitized.replaceAll("_+$", "");
        return sanitized;

    }

    /**
     * 解析Excel文件
     * @param inputStream Excel文件输入流
     * @return Excel文件内容列表，每行数据为一个列表
     */
    private List<List<String>> parseExcel(InputStream inputStream) {
        List<List<String>> result = new ArrayList<>();
        EasyExcel.read(inputStream, new ReadListener<Map<Integer, String>>() {
            @Override
            public void invoke(Map<Integer, String> data, AnalysisContext context) {
                List<String> row = new ArrayList<>();
                // 获取当前行的最大索引
                int maxIndex = data.keySet().stream().max(Integer::compareTo).orElse(-1);
                // 按顺序填充每一列
                for (int i = 0; i <= maxIndex; i++) {
                    String value = data.getOrDefault(i, "");
                    row.add(value != null ? value : "");
                }
                result.add(row);
            }

            @Override
            public void doAfterAllAnalysed(AnalysisContext context) {
                log.info("Excel解析完成，共 {} 行", result.size());
            }
            //EasyExcel 默认将第一行视为表头，不会通过 ReadListener.invoke() 回调返回。所以 parseExcel 返回的数据实际上是从 Excel 的第二行开始的。
            //需要设置 headRowNumber(0) 告诉 EasyExcel 从第一行就开始读取数据
        }).headRowNumber(0).sheet().doRead();
        return result;
    }

    @Override
    public boolean supports(FileType fileType, KnowledgeBaseType knowledgeBaseType) {
        /**
         * 只有Excel和CSV文件，并且知识库类型为数据查询时支持
         */
        if(FileType.EXCEL.equals(fileType) || FileType.CSV.equals(fileType)){
            return knowledgeBaseType == KnowledgeBaseType.DATA_QUERY;
        }
        return false;
    }

    /**
     * 列信息内部类
     */
    public static class ColumnInfo {
        // 列索引
        private int index;
        // 原始列标题
        private String originalHeader;
        // 列名
        private String columnName;
        // 列数据类型
        private String dataType;

        public int getIndex() {
            return index;
        }

        public void setIndex(int index) {
            this.index = index;
        }

        public String getOriginalHeader() {
            return originalHeader;
        }

        public void setOriginalHeader(String originalHeader) {
            this.originalHeader = originalHeader;
        }

        public String getColumnName() {
            return columnName;
        }

        public void setColumnName(String columnName) {
            this.columnName = columnName;
        }

        public String getDataType() {
            return dataType;
        }

        public void setDataType(String dataType) {
            this.dataType = dataType;
        }
    }
}
