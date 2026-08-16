package cn.john.dh.assistant.constant;

/**
 * 数据查询（DATA_QUERY）知识库相关常量
 *
 * @Author John
 * @Date 2026-08-08
 */
public class DataQueryConstant {

    /**
     * 数据查询物理表名前缀。
     * Excel/CSV 导入时由文件名生成物理表名（dh_data_query_xxx），
     * table_meta.table_name 与动态表均使用该前缀；
     * knowledge_document.table_name 存储的是不带前缀的业务表名，
     * 查询 table_meta 或执行动态 SQL 时需补全该前缀。
     */
    public static final String TABLE_PREFIX = "dh_data_query_";
}
