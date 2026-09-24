// 源: deploy/sql/01-schema.sql  sha256[:16]=7baf623506344ffd
package com.tm.im.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tm.im.domain.enums.PushMode;


/**
 * Agent 专属扩展
 *
 * <p>由 <code>tools/gen_entities.py</code> 从 <code>deploy/sql/01-schema.sql</code> 生成，
 * 请勿手工编辑——改结构请改 DDL 生成器后重跑。
 */
@TableName("agent_profile")
public class AgentProfile {

    @TableId(value = "actor_id", type = IdType.INPUT)
    private Long actorId;

    /** 归属人类 */
    private Long ownerActor;

    /** webhook 回调 */
    private String endpointUrl;

    /** 1=WEBHOOK 2=WS 3=PULL */
    private PushMode pushMode;

    /** ["text","image"] */
    private String capabilities;

    private String modelInfo;

    /** 每秒配额 */
    private Integer rateLimit;

    public Long getActorId() {
        return actorId;
    }

    public void setActorId(Long actorId) {
        this.actorId = actorId;
    }

    public Long getOwnerActor() {
        return ownerActor;
    }

    public void setOwnerActor(Long ownerActor) {
        this.ownerActor = ownerActor;
    }

    public String getEndpointUrl() {
        return endpointUrl;
    }

    public void setEndpointUrl(String endpointUrl) {
        this.endpointUrl = endpointUrl;
    }

    public PushMode getPushMode() {
        return pushMode;
    }

    public void setPushMode(PushMode pushMode) {
        this.pushMode = pushMode;
    }

    public String getCapabilities() {
        return capabilities;
    }

    public void setCapabilities(String capabilities) {
        this.capabilities = capabilities;
    }

    public String getModelInfo() {
        return modelInfo;
    }

    public void setModelInfo(String modelInfo) {
        this.modelInfo = modelInfo;
    }

    public Integer getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(Integer rateLimit) {
        this.rateLimit = rateLimit;
    }

    /**
     * 凭据类字段（secret / hash / token / password）一律脱敏。
     * 实体被随手打进日志是极常见的事，脱敏只有放在这里才拦得住。
     */
    @Override
    public String toString() {
        return "AgentProfile{" +
                "actorId=" + actorId + ", " +
                "ownerActor=" + ownerActor + ", " +
                "endpointUrl=" + endpointUrl + ", " +
                "pushMode=" + pushMode + ", " +
                "capabilities=" + capabilities + ", " +
                "modelInfo=" + modelInfo + ", " +
                "rateLimit=" + rateLimit
                + '}';
    }
}
