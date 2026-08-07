package cn.john.dh.assistant.rag.controller;

import cn.john.dh.assistant.common.R;
import cn.john.dh.assistant.rag.domain.dto.KnowledgeSegmentUpdateDTO;
import cn.john.dh.assistant.rag.domain.entity.KnowledgeDocument;
import cn.john.dh.assistant.rag.domain.entity.KnowledgeSegment;
import cn.john.dh.assistant.rag.service.KnowledgeDocumentService;
import cn.john.dh.assistant.rag.service.KnowledgeSegmentService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识分段查询接口
 *
 * @Author John
 * @Date 2026-08-06
 */
@RestController
@RequestMapping("/knowledge/segment")
public class KnowledgeSegmentController {

    @Autowired
    private KnowledgeSegmentService knowledgeSegmentService;

    @Autowired
    private KnowledgeDocumentService knowledgeDocumentService;

    /**
     * 分页查询指定文档的分段列表（仅查询当前生效版本的分段，按分段顺序升序）
     *
     * @param docId   文档ID
     * @param current 当前页
     * @param size    每页大小（默认20条）
     * @return 分段分页结果
     */
    @GetMapping("/page")
    public R<Page<KnowledgeSegment>> page(
            @RequestParam("docId") Long docId,
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "20") Integer size) {
        KnowledgeDocument document = knowledgeDocumentService.getById(docId);
        if (document == null) {
            return R.fail("文档不存在: docId=" + docId);
        }
        Page<KnowledgeSegment> page = new Page<>(current, size);
        // 文档尚未切分或当前版本未生成分段时，直接返回空分页
        if (document.getCurrentVersionId() == null) {
            return R.ok(page);
        }
        QueryWrapper<KnowledgeSegment> wrapper = new QueryWrapper<>();
        wrapper.eq("document_id", docId);
        wrapper.eq("document_version", document.getCurrentVersionId());
        wrapper.orderByAsc("chunk_order");
        return R.ok(knowledgeSegmentService.page(page, wrapper));
    }

    /**
     * 更新分段文本内容
     * <p>
     * 父分段（skipEmbedding=1）禁止直接编辑；子分段修改后自动同步父分段文本并重新向量化。
     *
     * @param dto 分段编辑请求（id + text）
     * @return 更新结果
     */
    @PutMapping("/update")
    public R<Boolean> update(@RequestBody KnowledgeSegmentUpdateDTO dto) {
        if (dto.getId() == null) {
            return R.fail("分段ID不能为空");
        }
        if (dto.getText() == null) {
            return R.fail("分段内容不能为空");
        }
        return R.ok(knowledgeSegmentService.updateSegmentText(dto.getId(), dto.getText()));
    }
}
