package cn.john.dh.assistant.rag.strategy.service;

import cn.john.dh.assistant.rag.domain.enums.FileType;
import cn.john.dh.assistant.rag.domain.enums.KnowledgeBaseType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * @Author John
 * @Date 2026-08-04 11:04
 */
@Service
@Slf4j
public class PdfProcessServiceImpl extends MinerUProcessBaseServiceImpl{

    @Override
    public boolean supports(FileType fileType, KnowledgeBaseType knowledgeBaseType) {
        return fileType == FileType.PDF;
    }
}
