package cn.john.dh.assistant.rag.domain.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 动态表数据预览结果
 * <p>
 * 用于「数据查询」类型文档的「查看数据」功能，
 * 返回列信息、分页数据和分页元数据。
 *
 * @Author John
 */
@Data
public class TableDataPreviewVO {

    /** 列信息列表（从 table_meta.columns_info 解析） */
    private List<Map<String, Object>> columns;

    /** 数据记录列表 */
    private List<Map<String, Object>> records;

    /** 总记录数 */
    private long total;

    /** 当前页码 */
    private long current;

    /** 每页条数 */
    private long size;

    /** 总页数 */
    private long pages;
}
