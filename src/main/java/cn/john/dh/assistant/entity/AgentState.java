package cn.john.dh.assistant.entity;

import java.util.ArrayList;
import java.util.List;

/**
 *
 * @Author John
 * @Date 2026-07-21 18:08
 */

public class AgentState {

    // 搜索结果列表，初始化为空
    private final List<SearchResult> searchResults = new ArrayList<>();

    /**
     * 获取所有搜索结果
     *
     * @return 搜索结果列表
     */
    public List<SearchResult> getSearchResults() {
        // 返回搜索结果列表
        return searchResults;
    }

    /**
     * 添加单条搜索结果
     *
     * @param result 搜索结果
     */
    public void addSearchResult(SearchResult result) {
        // 将结果追加到列表末尾
        searchResults.add(result);
    }
}
