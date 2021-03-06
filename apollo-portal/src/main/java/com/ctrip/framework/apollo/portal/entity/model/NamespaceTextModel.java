package com.ctrip.framework.apollo.portal.entity.model;

import com.ctrip.framework.apollo.core.enums.ConfigFileFormat;
import com.ctrip.framework.apollo.core.enums.Env;
import com.ctrip.framework.apollo.core.utils.StringUtils;

/**
 * Namespace 下的配置文件 Model
 */
public class NamespaceTextModel implements Verifiable {

    /**
     * App 编号
     */
    private String appId;
    /**
     * Env 名
     */
    private String env;
    /**
     * Cluster 名
     */
    private String clusterName;
    /**
     * Namespace 名
     */
    private String namespaceName;
    /**
     * Namespace 编号
     */
    private int namespaceId;
    /**
     * 格式
     */
    private String format;
    /**
     * 配置文本
     */
    private String configText;

    @Override
    public boolean isInvalid() {
        return StringUtils.isContainEmpty(appId, env, clusterName, namespaceName) || namespaceId <= 0;
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public Env getEnv() {
        return Env.valueOf(env);
    }

    public void setEnv(String env) {
        this.env = env;
    }

    public String getClusterName() {
        return clusterName;
    }

    public void setClusterName(String clusterName) {
        this.clusterName = clusterName;
    }

    public String getNamespaceName() {
        return namespaceName;
    }

    public void setNamespaceName(String namespaceName) {
        this.namespaceName = namespaceName;
    }

    public int getNamespaceId() {
        return namespaceId;
    }

    public void setNamespaceId(int namespaceId) {
        this.namespaceId = namespaceId;
    }

    public String getConfigText() {
        return configText;
    }

    public void setConfigText(String configText) {
        this.configText = configText;
    }

    public ConfigFileFormat getFormat() {
        return ConfigFileFormat.fromString(this.format);
    }

    public void setFormat(String format) {
        this.format = format;
    }
}
