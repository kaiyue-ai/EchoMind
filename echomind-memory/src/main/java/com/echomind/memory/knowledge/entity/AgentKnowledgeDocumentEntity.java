package com.echomind.memory.knowledge.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Agent 知识库文档表。
 *
 * <p>一个 Agent 可以拥有多份 txt/pdf 文档。文档表保存文件级元数据，
 * 切片正文和向量统一进入 Milvus。</p>
 */
@TableName("echomind_agent_knowledge_documents")
@Getter
@Setter
public class AgentKnowledgeDocumentEntity {

    /** 文档自增 ID。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属 Agent。 */
    @TableField("agent_id")
    private String agentId;

    /** 用户上传时的文件名。 */
    @TableField("file_name")
    private String fileName;

    /** 文件类型，目前支持 txt/pdf。 */
    @TableField("file_type")
    private String fileType;

    /** 原始文件大小，单位字节。 */
    @TableField("file_size")
    private long fileSize;

    /** 原始文件对象存储 URI，旧数据可能为空。 */
    @TableField("object_uri")
    private String objectUri;

    /** 原始文件 Content-Type。 */
    @TableField("content_type")
    private String contentType;

    /** 成功切出来的片段数。 */
    @TableField("chunk_count")
    private int chunkCount;

    /** 创建时间。 */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    /** 更新时间。 */
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
