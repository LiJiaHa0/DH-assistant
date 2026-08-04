package cn.john.dh.assistant.rag.controller;

import cn.john.dh.assistant.common.R;
import cn.john.dh.assistant.rag.domain.entity.TableMeta;
import cn.john.dh.assistant.rag.service.TableMetaService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

    /**
     * 分页查询表元数据（支持表名模糊搜索）
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
        QueryWrapper<TableMeta> wrapper = new QueryWrapper<>();
        if (tableName != null && !tableName.isEmpty()) {
            wrapper.like("table_name", tableName);
        }
        wrapper.orderByDesc("created_at");
        return R.ok(tableMetaService.page(page, wrapper));
    }

    /**
     * 条件查询列表（不分页）
     *
     * @param tableName 表名（模糊查询，可选）
     * @param versionId 文档版本ID（可选）
     * @return 表元数据列表
     */
    @GetMapping("/list")
    public R<List<TableMeta>> list(
            @RequestParam(value = "tableName", required = false) String tableName,
            @RequestParam(value = "versionId", required = false) Long versionId) {
        QueryWrapper<TableMeta> wrapper = new QueryWrapper<>();
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
     * 根据ID查询
     *
     * @param id 主键ID
     * @return 表元数据
     */
    @GetMapping("/{id}")
    public R<TableMeta> getById(@PathVariable Long id) {
        return R.ok(tableMetaService.getById(id));
    }

    /**
     * 根据表名查询
     *
     * @param tableName 表名
     * @return 表元数据
     */
    @GetMapping("/by-table-name")
    public R<TableMeta> getByTableName(@RequestParam String tableName) {
        return R.ok(tableMetaService.getByTableName(tableName));
    }

    /**
     * 根据文档版本ID查询列表
     *
     * @param versionId 文档版本ID（knowledge_document_version.version_id）
     * @return 表元数据列表
     */
    @GetMapping("/list-by-version")
    public R<List<TableMeta>> listByVersionId(@RequestParam Long versionId) {
        return R.ok(tableMetaService.listByVersionId(versionId));
    }

    /**
     * 查询当前活跃的表元数据（暴露给 Text2SQL 的动态表）
     *
     * @return 活跃表元数据列表
     */
    @GetMapping("/active")
    public R<List<TableMeta>> listActiveForQuery() {
        return R.ok(tableMetaService.listActiveForQuery());
    }

    /**
     * 新增表元数据
     *
     * @param tableMeta 表元数据
     * @return 是否新增成功
     */
    @PostMapping
    public R<Boolean> save(@RequestBody TableMeta tableMeta) {
        return R.ok(tableMetaService.save(tableMeta));
    }

    /**
     * 根据ID更新（需携带 lockVersion 乐观锁版本号）
     *
     * @param tableMeta 表元数据
     * @return 是否更新成功
     */
    @PutMapping
    public R<Boolean> updateById(@RequestBody TableMeta tableMeta) {
        return R.ok(tableMetaService.updateById(tableMeta));
    }

    /**
     * 根据ID删除
     *
     * @param id 主键ID
     * @return 是否删除成功
     */
    @DeleteMapping("/{id}")
    public R<Boolean> removeById(@PathVariable Long id) {
        return R.ok(tableMetaService.removeById(id));
    }

    /**
     * 批量删除
     *
     * @param ids 主键ID列表
     * @return 是否删除成功
     */
    @DeleteMapping("/batch")
    public R<Boolean> removeByIds(@RequestParam List<Long> ids) {
        return R.ok(tableMetaService.removeByIds(ids));
    }
}
