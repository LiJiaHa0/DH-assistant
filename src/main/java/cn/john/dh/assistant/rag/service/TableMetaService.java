package cn.john.dh.assistant.rag.service;

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
}
