package cn.john.dh.assistant.rag.strategy.service;

import cn.john.dh.assistant.rag.domain.entity.KnowledgeDocument;
import cn.john.dh.assistant.rag.domain.enums.FileType;
import cn.john.dh.assistant.rag.domain.enums.KnowledgeBaseType;
import cn.john.dh.assistant.rag.mapper.TableMetaMapper;
import cn.john.dh.assistant.rag.strategy.FileProcessService;
import com.alibaba.druid.sql.transform.TableMapping;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.formula.functions.T;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.io.InputStream;
import java.util.*;

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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String processDocument(KnowledgeDocument document, InputStream inputStream) {
        String documentTitle = document.getDocTitle();
        String originalTableName = document.getTableName();
        Long versionId = document.getCurrentVersionId();
        Assert.notNull(versionId, "文档当前版本ID不能为空");
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
        } catch (Exception e) {
            log.error("处理Excel文件时出错: {}, versionId={}", documentTitle, versionId, e);
        }
        return "";
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
            column.setDataType("VARCHAR(500)"); // 默认使用VARCHAR类型
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
