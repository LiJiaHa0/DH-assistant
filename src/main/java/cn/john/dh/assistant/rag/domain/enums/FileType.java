package cn.john.dh.assistant.rag.domain.enums;

/**
 * @Author John
 * @Date 2026-07-31 17:53
 */
public enum FileType {

    PDF("pdf"),
    DOC("doc"),
    TXT("txt"),
    HTML("html"),
    MARKDOWN("markdown"),
    CSV("csv"),
    EXCEL("excel");

    private final String type;

    FileType(String type) {
        this.type = type;
    }

    public String getType() {
        return type;
    }
}
