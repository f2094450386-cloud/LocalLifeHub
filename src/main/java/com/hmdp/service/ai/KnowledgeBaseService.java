package com.hmdp.service.ai;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class KnowledgeBaseService {

    private static final String KNOWLEDGE_PATH = "classpath:ai-knowledge/*.txt";

    private final List<KnowledgeEntry> entries = new ArrayList<>();

    @PostConstruct
    public void loadKnowledge() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(KNOWLEDGE_PATH);
            for (Resource resource : resources) {
                KnowledgeEntry entry = parseEntry(resource);
                if (entry != null) {
                    entries.add(entry);
                    log.info("Loaded knowledge entry: {}", entry.title);
                }
            }
            log.info("Knowledge base loaded: {} entries", entries.size());
        } catch (Exception e) {
            log.error("Failed to load knowledge base", e);
        }
    }

    public String search(String query) {
        if (StrUtil.isBlank(query) || entries.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (KnowledgeEntry entry : entries) {
            if (matches(query, entry)) {
                sb.append("【").append(entry.title).append("】\n");
                sb.append(entry.content).append("\n\n");
            }
        }
        return sb.toString();
    }

    private boolean matches(String query, KnowledgeEntry entry) {
        for (String keyword : entry.keywords) {
            if (query.contains(keyword)) {
                return true;
            }
        }
        return query.contains(entry.title);
    }

    private KnowledgeEntry parseEntry(Resource resource) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String titleLine = reader.readLine();
            if (titleLine == null || !titleLine.startsWith("# ")) {
                return null;
            }
            String title = titleLine.substring(2).trim();

            String keywordLine = reader.readLine();
            String[] keywords = keywordLine != null ? keywordLine.trim().split("\\s+") : new String[0];
            keywords = filterEmpty(keywords);

            StringBuilder contentBuilder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (contentBuilder.length() > 0) {
                    contentBuilder.append("\n");
                }
                contentBuilder.append(line);
            }
            String content = contentBuilder.toString().trim();

            if (StrUtil.isBlank(title) || StrUtil.isBlank(content)) {
                return null;
            }
            return new KnowledgeEntry(title, keywords, content);
        } catch (Exception e) {
            log.error("Failed to parse knowledge entry: {}", resource.getFilename(), e);
            return null;
        }
    }

    private String[] filterEmpty(String[] arr) {
        int count = 0;
        for (String s : arr) {
            if (!s.isEmpty()) count++;
        }
        String[] result = new String[count];
        int i = 0;
        for (String s : arr) {
            if (!s.isEmpty()) result[i++] = s;
        }
        return result;
    }

    private static class KnowledgeEntry {
        final String title;
        final String[] keywords;
        final String content;

        KnowledgeEntry(String title, String[] keywords, String content) {
            this.title = title;
            this.keywords = keywords;
            this.content = content;
        }
    }
}
