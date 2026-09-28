package com.tm.im.admin;

import com.tm.im.core.admin.AdminService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时创建第一个后台账号（只在 {@code admin_user} 表为空时生效）。
 *
 * <p><b>为什么需要它</b>：没有这一步，第一个管理员只能靠手工 {@code INSERT}，
 * 而 {@code password_hash} 的格式是 {@code pbkdf2-sha256$迭代数$盐$摘要}——
 * 手拼错了不会有任何报错，只会表现成「怎么登都登不上」（40101）。
 * 那是一次注定要发生的排查。
 *
 * <p><b>为什么是 {@code ApplicationRunner} 而不是 {@code @PostConstruct}</b>：
 * Runner 在容器完全就绪之后执行，那时事务代理、数据源、连接池都已可用；
 * 而 {@code @PostConstruct} 阶段拿到的可能是一个还没完成初始化的 Bean，
 * 失败信息会指向一个与「第一个账号」无关的地方。
 *
 * <p><b>失败不让应用启动不了</b>：建号失败（SQL 异常）说明库有问题，
 * 而那件事会在第一个请求上以更清楚的形式暴露；这里若抛出，
 * 表现会是「后台起不来」，排查方向从「库」偏到「启动配置」。
 */
@Component
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final AdminService admins;

    public AdminBootstrapRunner(AdminService admins) {
        this.admins = admins;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            if (admins.bootstrap()) {
                log.warn("后台首个账号已创建 —— 请登录后立刻改口令，"
                        + "并从部署配置里删掉 tm.admin.bootstrap.*");
            }
        } catch (RuntimeException e) {
            log.error("创建首个后台账号失败（后台仍会启动，但可能没人能登录）：{}", e.toString(), e);
        }
    }
}
