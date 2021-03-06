package com.ctrip.framework.apollo.openapi.service;

import com.ctrip.framework.apollo.common.exception.BadRequestException;
import com.ctrip.framework.apollo.openapi.entity.Consumer;
import com.ctrip.framework.apollo.openapi.entity.ConsumerAudit;
import com.ctrip.framework.apollo.openapi.entity.ConsumerRole;
import com.ctrip.framework.apollo.openapi.entity.ConsumerToken;
import com.ctrip.framework.apollo.openapi.repository.ConsumerAuditRepository;
import com.ctrip.framework.apollo.openapi.repository.ConsumerRepository;
import com.ctrip.framework.apollo.openapi.repository.ConsumerRoleRepository;
import com.ctrip.framework.apollo.openapi.repository.ConsumerTokenRepository;
import com.ctrip.framework.apollo.portal.component.config.PortalConfig;
import com.ctrip.framework.apollo.portal.entity.bo.UserInfo;
import com.ctrip.framework.apollo.portal.entity.po.Role;
import com.ctrip.framework.apollo.portal.service.RolePermissionService;
import com.ctrip.framework.apollo.portal.spi.UserInfoHolder;
import com.ctrip.framework.apollo.portal.spi.UserService;
import com.ctrip.framework.apollo.portal.util.RoleUtils;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.hash.Hashing;
import org.apache.commons.lang.time.FastDateFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * @author Jason Song(song_s@ctrip.com)
 */
@Service
public class ConsumerService {

    private static final FastDateFormat TIMESTAMP_FORMAT = FastDateFormat.getInstance("yyyyMMddHHmmss");
    private static final Joiner KEY_JOINER = Joiner.on("|");

    @Autowired
    private UserInfoHolder userInfoHolder;
    @Autowired
    private ConsumerTokenRepository consumerTokenRepository;
    @Autowired
    private ConsumerRepository consumerRepository;
    @Autowired
    private ConsumerAuditRepository consumerAuditRepository;
    @Autowired
    private ConsumerRoleRepository consumerRoleRepository;
    @Autowired
    private PortalConfig portalConfig;
    @Autowired
    private RolePermissionService rolePermissionService;
    @Autowired
    private UserService userService;

    public Consumer createConsumer(Consumer consumer) {
        String appId = consumer.getAppId();

        // 校验 appId 对应的 Consumer 不存在
        Consumer managedConsumer = consumerRepository.findByAppId(appId);
        if (managedConsumer != null) {
            throw new BadRequestException("Consumer already exist");
        }

        // 校验 ownerName 对应的 UserInfo 存在
        String ownerName = consumer.getOwnerName();
        UserInfo owner = userService.findByUserId(ownerName);
        if (owner == null) {
            throw new BadRequestException(String.format("User does not exist. UserId = %s", ownerName));
        }
        consumer.setOwnerEmail(owner.getEmail());

        // 设置 Consumer 的创建和最后修改人为当前管理员
        String operator = userInfoHolder.getUser().getUserId();
        consumer.setDataChangeCreatedBy(operator);
        consumer.setDataChangeLastModifiedBy(operator);

        // 保存 Consumer 到数据库中
        return consumerRepository.save(consumer);
    }

    public ConsumerToken generateAndSaveConsumerToken(Consumer consumer, Date expires) {
        Preconditions.checkArgument(consumer != null, "Consumer can not be null");

        // 生成 ConsumerToken 对象
        ConsumerToken consumerToken = generateConsumerToken(consumer, expires);
        consumerToken.setId(0); //for protection

        // 保存 ConsumerToken 到数据库中
        return consumerTokenRepository.save(consumerToken);
    }

    public ConsumerToken getConsumerTokenByAppId(String appId) {
        Consumer consumer = consumerRepository.findByAppId(appId);
        if (consumer == null) {
            return null;
        }

        return consumerTokenRepository.findByConsumerId(consumer.getId());
    }

    public Long getConsumerIdByToken(String token) {
        if (Strings.isNullOrEmpty(token)) {
            return null;
        }
        ConsumerToken consumerToken = consumerTokenRepository.findTopByTokenAndExpiresAfter(token, new Date());
        return consumerToken == null ? null : consumerToken.getConsumerId();
    }

    public Consumer getConsumerByConsumerId(long consumerId) {
        return consumerRepository.findOne(consumerId);
    }

    // 授权 Namespace 的 Role 给 Consumer
    @Transactional
    public List<ConsumerRole> assignNamespaceRoleToConsumer(String token, String appId, String namespaceName) {
        // 校验 Token 是否有对应的 Consumer 。若不存在，抛出 BadRequestException 异常
        Long consumerId = getConsumerIdByToken(token);
        if (consumerId == null) {
            throw new BadRequestException("Token is Illegal");
        }

        // 获得 Namespace 对应的 Role 们。若有任一不存在，抛出 BadRequestException 异常
        Role namespaceModifyRole = rolePermissionService.findRoleByRoleName(RoleUtils.buildModifyNamespaceRoleName(appId, namespaceName));
        Role namespaceReleaseRole = rolePermissionService.findRoleByRoleName(RoleUtils.buildReleaseNamespaceRoleName(appId, namespaceName));
        if (namespaceModifyRole == null || namespaceReleaseRole == null) {
            throw new BadRequestException("Namespace's role does not exist. Please check whether namespace has created.");
        }
        long namespaceModifyRoleId = namespaceModifyRole.getId();
        long namespaceReleaseRoleId = namespaceReleaseRole.getId();

        // 获得 Consumer 对应的 ConsumerRole 们。若都存在，返回 ConsumerRole 数组
        ConsumerRole managedModifyRole = consumerRoleRepository.findByConsumerIdAndRoleId(consumerId, namespaceModifyRoleId);
        ConsumerRole managedReleaseRole = consumerRoleRepository.findByConsumerIdAndRoleId(consumerId, namespaceReleaseRoleId);
        if (managedModifyRole != null && managedReleaseRole != null) {
            return Arrays.asList(managedModifyRole, managedReleaseRole);
        }

        // 创建 Consumer 对应的 ConsumerRole 们
        String operator = userInfoHolder.getUser().getUserId();
        ConsumerRole namespaceModifyConsumerRole = createConsumerRole(consumerId, namespaceModifyRoleId, operator);
        ConsumerRole namespaceReleaseConsumerRole = createConsumerRole(consumerId, namespaceReleaseRoleId, operator);
        // 保存 Consumer 对应的 ConsumerRole 们到数据库中
        ConsumerRole createdModifyConsumerRole = consumerRoleRepository.save(namespaceModifyConsumerRole);
        ConsumerRole createdReleaseConsumerRole = consumerRoleRepository.save(namespaceReleaseConsumerRole);
        // 返回 ConsumerRole 数组
        return Arrays.asList(createdModifyConsumerRole, createdReleaseConsumerRole);
    }

    // 授权 App 的 Role 给 Consumer
    @Transactional
    public ConsumerRole assignAppRoleToConsumer(String token, String appId) {
        // 校验 Token 是否有对应的 Consumer 。若不存在，抛出 BadRequestException 异常
        Long consumerId = getConsumerIdByToken(token);
        if (consumerId == null) {
            throw new BadRequestException("Token is Illegal");
        }

        // 获得 App 对应的 Role 对象
        Role masterRole = rolePermissionService.findRoleByRoleName(RoleUtils.buildAppMasterRoleName(appId));
        if (masterRole == null) {
            throw new BadRequestException("App's role does not exist. Please check whether app has created.");
        }

        // 获得 Consumer 对应的 ConsumerRole 对象。若已存在，返回 ConsumerRole 对象
        long roleId = masterRole.getId();
        ConsumerRole managedModifyRole = consumerRoleRepository.findByConsumerIdAndRoleId(consumerId, roleId);
        if (managedModifyRole != null) {
            return managedModifyRole;
        }

        // 创建 Consumer 对应的 ConsumerRole 对象
        String operator = userInfoHolder.getUser().getUserId();
        ConsumerRole consumerRole = createConsumerRole(consumerId, roleId, operator);
        // 保存 Consumer 对应的 ConsumerRole 对象
        return consumerRoleRepository.save(consumerRole);
    }

    @Transactional
    public void createConsumerAudits(Iterable<ConsumerAudit> consumerAudits) {
        consumerAuditRepository.save(consumerAudits);
    }

    @Transactional // 测试方法使用
    public ConsumerToken createConsumerToken(ConsumerToken entity) {
        entity.setId(0); //for protection

        return consumerTokenRepository.save(entity);
    }

    private ConsumerToken generateConsumerToken(Consumer consumer, Date expires) {
        long consumerId = consumer.getId();
        String createdBy = userInfoHolder.getUser().getUserId();
        Date createdTime = new Date();

        // 创建 ConsumerToken
        ConsumerToken consumerToken = new ConsumerToken();
        consumerToken.setConsumerId(consumerId);
        consumerToken.setExpires(expires);
        consumerToken.setDataChangeCreatedBy(createdBy);
        consumerToken.setDataChangeCreatedTime(createdTime);
        consumerToken.setDataChangeLastModifiedBy(createdBy);
        consumerToken.setDataChangeLastModifiedTime(createdTime);

        // 生成 ConsumerToken 的 `token`
        generateAndEnrichToken(consumer, consumerToken);

        return consumerToken;
    }

    void generateAndEnrichToken(Consumer consumer, ConsumerToken consumerToken) {
        Preconditions.checkArgument(consumer != null);

        // 设置创建时间
        if (consumerToken.getDataChangeCreatedTime() == null) {
            consumerToken.setDataChangeCreatedTime(new Date());
        }
        // 生成 ConsumerToken 的 `token`
        consumerToken.setToken(generateToken(consumer.getAppId(), consumerToken.getDataChangeCreatedTime(), portalConfig.consumerTokenSalt()));
    }

    /**
     * 生成 {@link ConsumerToken} 的 Token
     *
     * @param consumerAppId     Consumer App 编号
     * @param generationTime    生成时间
     * @param consumerTokenSalt Salt
     * @return Token
     */
    String generateToken(String consumerAppId, Date generationTime, String consumerTokenSalt) {
        return Hashing.sha1().hashString(KEY_JOINER.join(consumerAppId, TIMESTAMP_FORMAT.format(generationTime), consumerTokenSalt), Charsets.UTF_8).toString();
    }

    ConsumerRole createConsumerRole(Long consumerId, Long roleId, String operator) {
        ConsumerRole consumerRole = new ConsumerRole();
        consumerRole.setConsumerId(consumerId);
        consumerRole.setRoleId(roleId);
        consumerRole.setDataChangeCreatedBy(operator);
        consumerRole.setDataChangeLastModifiedBy(operator);
        return consumerRole;
    }

}
