package cn.john.dh.assistant.rag.controller;

import cn.dev33.satoken.stp.StpUtil;
import cn.john.dh.assistant.common.BusinessException;
import cn.john.dh.assistant.common.R;
import cn.john.dh.assistant.rag.domain.dto.TableDataPreviewVO;
import cn.john.dh.assistant.rag.domain.entity.KnowledgeDocument;
import cn.john.dh.assistant.rag.domain.entity.KnowledgeDocumentVersion;
import cn.john.dh.assistant.rag.domain.entity.TableMeta;
import cn.john.dh.assistant.rag.service.KnowledgeDocumentService;
import cn.john.dh.assistant.rag.service.KnowledgeDocumentVersionService;
import cn.john.dh.assistant.rag.service.TableMetaService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Objects;

/**
 * 表元数据管理接口
 *
 * @Author John
 * @Date 2026-08-04
 */
@RestController
@RequestMapping("/table-meta")
public class TableMetaController {

    @Autowired
    private TableMetaService tableMetaService;

    @Autowired
    private KnowledgeDocumentService knowledgeDocumentService;

    @Autowired
    private KnowledgeDocumentVersionService knowledgeDocumentVersionService;

    /**
     * 分页查询表元数据（仅当前用户文档关联的表，支持表名模糊搜索）
     *
     * @param current  当前页
     * @param size     每页大小
     * @param tableName 表名（模糊查询，可选）
     * @return 分页结果
     */
    @GetMapping("/page")
    public R<Page<TableMeta>> page(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(value = "tableName", required = false) String tableName) {
        Page<TableMeta> page = new Page<>(current, size);
        List<Long> versionIds = currentUserVersionIds();
        if (versionIds.isEmpty()) {
            return R.ok(page);
        }
        QueryWrapper<TableMeta> wrapper = new QueryWrapper<>();
        wrapper.in("version_id", versionIds);
        if (tableName != null && !tableName.isEmpty()) {
            wrapper.like("table_name", tableName);
        }
        wrapper.orderByDesc("created_at");
        return R.ok(tableMetaService.page(page, wrapper));
    }

    /**
     * 条件查询列表（不分页，仅当前用户文档关联的表）
     *
     * @param tableName 表名（模糊查询，可选）
     * @param versionId 文档版本ID（可选）
     * @return 表元数据列表
     */
    @GetMapping("/list")
    public R<List<TableMeta>> list(
            @RequestParam(value = "tableName", required = false) String tableName,
            @RequestParam(value = "versionId", required = false) Long versionId) {
        List<Long> versionIds = currentUserVersionIds();
        if (versionIds.isEmpty()) {
            return R.ok(List.of());
        }
        QueryWrapper<TableMeta> wrapper = new QueryWrapper<>();
        wrapper.in("version_id", versionIds);
        if (tableName != null && !tableName.isEmpty()) {
            wrapper.like("table_name", tableName);
        }
        if (versionId != null) {
            wrapper.eq("version_id", versionId);
        }
        wrapper.orderByDesc("created_at");
        return R.ok(tableMetaService.list(wrapper));
    }

    /**
     * 根据ID查询（仅当前用户文档关联的表）
     *
     * @param id 主键ID
     * @return 表元数据
     */
    @GetMapping("/{id}")
    public R<TableMeta> getById(@PathVariable Long id) {
        TableMeta meta = tableMetaService.getById(id);
        if (meta == null || !currentUserVersionIds().contains(meta.getVersionId())) {
            return R.fail("表元数据不存在");
        }
        return R.ok(meta);
    }

    /**
     * 根据表名查询（仅当前用户文档关联的表）
     *
     * @param tableName 表名
     * @return 表元数据
     */
    @GetMapping("/by-table-name")
    public R<TableMeta> getByTableName(@RequestParam String tableName) {
        TableMeta meta = tableMetaService.getByTableName(tableName);
        if (meta == null || !currentUserVersionIds().contains(meta.getVersionId())) {
            return R.fail("表元数据不存在");
        }
        return R.ok(meta);
    }

    /**
     * 根据文档版本ID查询列表（仅当前用户文档关联的版本）
     *
     * @param versionId 文档版本ID（knowledge_document_version.version_id）
     * @return 表元数据列表
     */
    @GetMapping("/list-by-version")
    public R<List<TableMeta>> listByVersionId(@RequestParam Long versionId) {
        if (!currentUserVersionIds().contains(versionId)) {
            throw new BusinessException("无权访问该版本的表元数据");
        }
        return R.ok(tableMetaService.listByVersionId(versionId));
    }

    /**
     * 查询当前活跃的表元数据（仅当前用户文档关联的动态表）
     *
     * @return 活跃表元数据列表
     */
    @GetMapping("/active")
    public R<List<TableMeta>> listActiveForQuery() {
        List<TableMeta> all = tableMetaService.listActiveForQuery();
        List<Long> versionIds = currentUserVersionIds();
        List<TableMeta> mine = all.stream()
                .filter(meta -> versionIds.contains(meta.getVersionId()))
                .toList();
        return R.ok(mine);
    }

    /**
     * 分页预览动态表数据（「数据查询」类型文档的「查看数据」功能，仅限文档所属用户）
     * <p>
     * 根据文档ID定位到对应的物理表（表名已包含 dh_data_query_ 前缀），
     * 返回列信息和分页数据。
     *
     * @param docId   文档ID（knowledge_document.doc_id）
     * @param current 当前页码（默认1）
     * @param size    每页条数（默认10）
     * @return 包含列信息、数据记录和分页元数据的预览结果
     */
    @GetMapping("/data/{docId}")
    public R<TableDataPreviewVO> previewData(
            @PathVariable Long docId,
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size) {
        KnowledgeDocument document = knowledgeDocumentService.getById(docId);
        if (document == null) {
            throw new BusinessException("文档不存在: docId=" + docId);
        }
        if (!Objects.equals(document.getUserId(), StpUtil.getLoginIdAsString())) {
            throw new BusinessException("无权访问该文档的数据");
        }
        return R.ok(tableMetaService.previewTableData(docId, current, size));
    }

    /**
     * 新增表元数据（绑定当前用户文档版本，防止任意写入）
     *
     * @param tableMeta 表元数据
     * @return 是否新增成功
     */
    @PostMapping
    public R<Boolean> save(@RequestBody TableMeta tableMeta) {
        if (tableMeta.getVersionId() == null || !currentUserVersionIds().contains(tableMeta.getVersionId())) {
            throw new BusinessException("无权为未关联当前用户的文档版本保存表元数据");
        }
        return R.ok(tableMetaService.save(tableMeta));
    }

    /**
     * 根据ID更新（需携带 lockVersion 乐观锁版本号，仅当前用户文档关联的表）
     *
     * @param tableMeta 表元数据
     * @return 是否更新成功
     */
    @PutMapping
    public R<Boolean> updateById(@RequestBody TableMeta tableMeta) {
        if (tableMeta.getId() == null) {
            throw new BusinessException("表元数据ID不能为空");
        }
        TableMeta existing = tableMetaService.getById(tableMeta.getId());
        if (existing == null || !currentUserVersionIds().contains(existing.getVersionId())) {
            throw new BusinessException("无权更新该表元数据");
        }
        // 防止篡改归属字段
        tableMeta.setVersionId(existing.getVersionId());
        return R.ok(tableMetaService.updateById(tableMeta));
    }

    /**
     * 根据ID删除（仅当前用户文档关联的表）
     *
     * @param id 主键ID
     * @return 是否删除成功
     */
    @DeleteMapping("/{id}")
    public R<Boolean> removeById(@PathVariable Long id) {
        TableMeta existing = tableMetaService.getById(id);
        if (existing == null || !currentUserVersionIds().contains(existing.getVersionId())) {
            throw new BusinessException("无权删除该表元数据");
        }
        return R.ok(tableMetaService.removeById(id));
    }

    /**
     * 批量删除（仅当前用户文档关联的表）
     *
     * @param ids 主键ID列表
     * @return 是否删除成功
     */
    @DeleteMapping("/batch")
    public R<Boolean> removeByIds(@RequestParam List<Long> ids) {
        List<Long> versionIds = currentUserVersionIds();
        for (Long id : ids) {
            TableMeta existing = tableMetaService.getById(id);
            if (existing == null || !versionIds.contains(existing.getVersionId())) {
                throw new BusinessException("无权删除表元数据: id=" + id);
            }
        }
        return R.ok(tableMetaService.removeByIds(ids));
    }

    /**
     * 获取当前用户文档关联的所有版本ID集合（用于 table_meta 按用户过滤）
     */
    private List<Long> currentUserVersionIds() {
        List<KnowledgeDocument> docs = knowledgeDocumentService.list(
                new QueryWrapper<KnowledgeDocument>().eq("user_id", StpUtil.getLoginIdAsString()));
        if (docs.isEmpty()) {
            return List.of();
        }
        List<Long> docIds = docs.stream().map(KnowledgeDocument::getDocId).toList();
        return knowledgeDocumentVersionService.list(
                        new QueryWrapper<KnowledgeDocumentVersion>().in("doc_id", docIds))
                .stream()
                .map(KnowledgeDocumentVersion::getId)
                .toList();
    }
}
