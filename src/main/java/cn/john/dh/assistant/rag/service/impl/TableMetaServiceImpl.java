package cn.john.dh.assistant.rag.service.impl;

import cn.john.dh.assistant.rag.domain.dto.TableDataPreviewVO;
import cn.john.dh.assistant.rag.domain.entity.KnowledgeDocument;
import cn.john.dh.assistant.rag.domain.entity.TableMeta;
import cn.john.dh.assistant.rag.mapper.KnowledgeDocumentMapper;
import cn.john.dh.assistant.rag.mapper.TableMetaMapper;
import cn.john.dh.assistant.rag.service.TableMetaService;
import cn.john.dh.assistant.utils.BusinessExceptionUtils;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 表元数据 Service 实现类
 *
 * @Author John
 * @Date 2026-08-04
 */
@Service
public class TableMetaServiceImpl extends ServiceImpl<TableMetaMapper, TableMeta> implements TableMetaService {

    @Autowired
    private KnowledgeDocumentMapper knowledgeDocumentMapper;

    @Override
    public TableMeta getByTableName(String tableName) {
        LambdaQueryWrapper<TableMeta> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TableMeta::getTableName, tableName);
        return this.getOne(wrapper);
    }

    @Override
    public List<TableMeta> listByVersionId(Long versionId) {
        LambdaQueryWrapper<TableMeta> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TableMeta::getVersionId, versionId);
        wrapper.orderByDesc(TableMeta::getCreatedAt);
        return this.list(wrapper);
    }

    @Override
    public List<TableMeta> listActiveForQuery() {
        // DATA_QUERY 同一逻辑表在所有版本中复用同一个物理表，
        // 因此只要表元数据存在且未被逻辑删除，就暴露给 Text2SQL。
        List<TableMeta> allMetas = list();
        if (CollectionUtils.isEmpty(allMetas)) {
            return Collections.emptyList();
        }
        return allMetas.stream()
                .filter(meta -> meta.getCreateSql() != null && !meta.getCreateSql().isBlank())
                .collect(Collectors.toList());
    }

    @Override
    public TableDataPreviewVO previewTableData(Long docId, int current, int size) {
        // 1. 根据文档ID查找文档，获取当前版本ID
        KnowledgeDocument document = knowledgeDocumentMapper.selectById(docId);
        BusinessExceptionUtils.throwBusinessException(Objects.isNull(document), "文档不存在: docId=" + docId);
        Long versionId = document.getCurrentVersionId();
        BusinessExceptionUtils.throwBusinessException(Objects.isNull(versionId), "文档尚未激活版本，无法查看数据");

        // 2. 根据版本ID查找表元数据（物理表名已包含 dh_data_query_ 前缀）
        List<TableMeta> metas = listByVersionId(versionId);
        BusinessExceptionUtils.throwBusinessException(CollectionUtils.isEmpty(metas), "未找到版本对应的表元数据，versionId=" + versionId);
        TableMeta tableMeta = metas.get(0);
        String physicalTableName = tableMeta.getTableName();

        // 3. 统计总记录数并分页查询数据
        long total = baseMapper.countTableData(physicalTableName);
        int offset = (current - 1) * size;
        List<Map<String, Object>> records = baseMapper.queryTableData(physicalTableName, offset, size);

        // 4. 解析列信息
        List<Map<String, Object>> columns = new ArrayList<>();
        if (tableMeta.getColumnsInfo() != null && !tableMeta.getColumnsInfo().isBlank()) {
            for (Object item : JSON.parseArray(tableMeta.getColumnsInfo())) {
                if (item instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> col = (Map<String, Object>) item;
                    columns.add(col);
                }
            }
        }

        // 5. 组装返回结果
        TableDataPreviewVO vo = new TableDataPreviewVO();
        vo.setColumns(columns);
        vo.setRecords(records);
        vo.setTotal(total);
        vo.setCurrent(current);
        vo.setSize(size);
        vo.setPages(total == 0 ? 1 : (total + size - 1) / size);
        return vo;
    }
}
