package cn.john.dh.assistant.rag.service.impl;

import cn.john.dh.assistant.rag.domain.entity.TableMeta;
import cn.john.dh.assistant.rag.mapper.TableMetaMapper;
import cn.john.dh.assistant.rag.service.TableMetaService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 表元数据 Service 实现类
 *
 * @Author John
 * @Date 2026-08-04
 */
@Service
public class TableMetaServiceImpl extends ServiceImpl<TableMetaMapper, TableMeta> implements TableMetaService {

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
}
