package cn.john.dh.assistant.rag.strategy;

import cn.john.dh.assistant.rag.domain.enums.FileType;
import cn.john.dh.assistant.rag.domain.enums.KnowledgeBaseType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @Author John
 * @Date 2026-08-03 16:23
 */
@Service
public class FileProcessServiceFactory {

    @Autowired
    private List<FileProcessService> fileProcessServiceList;

    public FileProcessService get(FileType fileProcessType, KnowledgeBaseType knowledgeBaseType) {
        return fileProcessServiceList.stream()
                .filter(service -> service.supports(fileProcessType, knowledgeBaseType))
                .findFirst().orElse(null);
    }
}
