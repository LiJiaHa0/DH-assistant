package cn.john.dh.assistant.rag.service;

import cn.john.dh.assistant.rag.domain.dto.TableDataPreviewVO;
import cn.john.dh.assistant.rag.domain.entity.TableMeta;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * 表元数据 Service 接口
 *
 * @Author John
 * @Date 2026-08-04
 */
public interface TableMetaService extends IService<TableMeta> {

    /**
     * 根据表名查询表元数据
     *
     * @param tableName 表名
     * @return 表元数据，不存在返回 null
     */
    TableMeta getByTableName(String tableName);

    /**
     * 根据文档版本ID查询表元数据列表
     *
     * @param versionId 文档版本ID（knowledge_document_version.version_id）
     * @return 表元数据列表
     */
    List<TableMeta> listByVersionId(Long versionId);

    /**
     * 查询当前应暴露给 Text2SQL 的动态表元数据。
     * <p>
     * 只返回建表语句不为空的表元数据，供 LLM 感知可查询的动态表。
     *
     * @return 活跃表元数据列表
     */
    List<TableMeta> listActiveForQuery();

    /**
     * 分页预览动态表数据（用于「数据查询」类型文档的「查看数据」功能）
     * <p>
     * 根据文档ID查找关联的表元数据，再对物理表执行分页查询。
     *
     * @param docId   文档ID（knowledge_document.doc_id）
     * @param current 当前页码（从1开始）
     * @param size    每页条数
     * @return 包含列信息、数据记录和分页元数据的预览结果
     */
    TableDataPreviewVO previewTableData(Long docId, int current, int size);
}
