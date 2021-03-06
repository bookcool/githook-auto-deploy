package com.sunshuhan.deploy;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Auto deploy when pushed code to git，代码推送自动部署
 */
@RestController
@EnableAutoConfiguration
@PropertySource(value = {"classpath:application.properties"})
@RequestMapping("/deploy")
public class Application {

    private static final Logger LOGGER = LoggerFactory.getLogger(Application.class);

    private static Map<String, Set<String>> gitPushEvenCache = new ConcurrentHashMap<>();

    private static Map<String, Boolean> gitHookEnableMap = new HashMap<>();

    private static Map<String, Boolean> deployEnableUserMap = new HashMap<>();

    private static Map<String, String> deployShellPathMap = new HashMap<>();

    @Value("${auto.deploy.branch}")
    private String autoDeployBranch;

    @Value("${deploy.shell.path.template}")
    private String deployShellPathTemplate;

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
        gitHookEnableMap.put("venus", true);
        gitHookEnableMap.put("site", true);
        gitHookEnableMap.put("qiwa", true);
        deployEnableUserMap.put("sunshuhan", true);
    }

    /**
     * manual deploy 手动执行重新发布
     *
     * @param projectName
     * @param emailAddress 执行人邮件地址
     * @return
     */
    @RequestMapping(value = "/manual/{project}", method = {RequestMethod.GET, RequestMethod.POST})
    @ResponseBody
    public String deploy(@PathVariable("project") String projectName,
                         @RequestParam(value = "email", required = false, defaultValue = "sunshuhan@souche.com") String emailAddress) {
        LOGGER.info("auto.deploy.branch :" + autoDeployBranch);
        LOGGER.info("deploy.shell.path.template :" + deployShellPathTemplate);
        reDeploy(projectName, emailAddress);
        return "success";
    }

    /**
     * auto deploy on-off 自动发布开关控制
     *
     * @param enable
     * @return
     */
    @RequestMapping(value = "/{project}/auto/deploy", method = {RequestMethod.GET, RequestMethod.POST})
    @ResponseBody
    public String autoDeployEnable(@PathVariable("project") String projectName,
                                   @RequestParam(value = "enable", required = false, defaultValue = "true") Boolean enable) {
        LOGGER.info(projectName + " git hook Enable:" + enable);
        if (enable == null) {
            return "fail";
        }
        gitHookEnableMap.put(projectName, enable);
        return "success";
    }

    /**
     * auto deploy user on-off 自动发布用户控制
     *
     * @param enable
     * @return
     */
    @RequestMapping(value = "/user/{user}/auto/deploy", method = {RequestMethod.GET, RequestMethod.POST})
    @ResponseBody
    public String autoDeployUserEnable(@PathVariable(value = "user") String userName,
                                       @RequestParam(value = "enable", required = false, defaultValue = "true") Boolean enable) {
        LOGGER.info(userName + " git hook user Enable:" + enable);
        if (enable == null) {
            return "fail";
        }
        if (userName != null && userName.length() > 0) {
            deployEnableUserMap.put(userName, enable);
        }
        return "success";
    }

    /**
     * set Deploy Shell 设置发布脚本路径
     *
     * @param projectName
     * @param path
     * @return
     */
    @RequestMapping(value = "/{project}/deploy/shell", method = {RequestMethod.GET, RequestMethod.POST})
    @ResponseBody
    public String setDeployShell(@PathVariable("project") String projectName,
                                 @RequestParam(value = "path") String path) {
        LOGGER.info(projectName + "set deploy shell:" + path);
        if (path == null) {
            return "fail";
        }
        deployShellPathMap.put(projectName, path);
        return "success";
    }

    /**
     * set Deploy Git Branch 设置需要自动发布的分支，默认 deploy-test
     *
     * @param branch
     * @return
     */
    @RequestMapping(value = "/git/branch", method = {RequestMethod.GET, RequestMethod.POST})
    @ResponseBody
    public String setDeployGitBranch(@RequestParam("branch") String branch) {
        LOGGER.info("set auto deploy git branch:" + branch);
        if (branch == null) {
            return "fail";
        }
        autoDeployBranch = branch;
        return "success";
    }

    /**
     * git hook api 回调的接口
     *
     * @param requestBodyMap
     * @param projectName
     * @return
     */
    @RequestMapping(value = "/{project}", method = {RequestMethod.POST})
    @ResponseBody
    public String deployByGitHook(@RequestBody Map<String, Object> requestBodyMap,
                                  @PathVariable("project") String projectName) {
        if (gitHookEnableMap.get(projectName) == null || !gitHookEnableMap.get(projectName)) {
            LOGGER.info(projectName + " hook api has stopped.");
            return "fail";
        }
        if (requestBodyMap == null) {
            return "fail";
        }

        String refBranch = Optional.ofNullable(requestBodyMap.get("ref")).orElse("").toString();

        //Body format github different from github
        String userName = Optional.ofNullable(requestBodyMap.get("user_name")).orElseGet(() -> githubUserName(requestBodyMap)).toString();
        String userEmail = Optional.ofNullable(requestBodyMap.get("user_email")).orElseGet(() -> githubUserEmail(requestBodyMap)).toString();
        String checkoutSha = Optional.ofNullable(requestBodyMap.get("checkout_sha")).orElse(requestBodyMap.get("after")).toString();

        String cacheKey = cacheKey(projectName, refBranch);
        Set<String> shaSet = gitPushEvenCache.computeIfAbsent(cacheKey, k -> new HashSet<>());
        if (shaSet.contains(checkoutSha)) {
            // exclude repetitive event  重复的事件，可能一次push多个重复事件
            // multi-user concurrent event 多个人同时push的情况
            LOGGER.info("repetitive event：" + checkoutSha);
            return "fail";
        }

        shaSet.add(checkoutSha);

        LOGGER.info(userName + " push code to ------------> " + projectName + ":" + refBranch);
        final String needRefBranch = "refs/heads/" + autoDeployBranch;
        if (!needRefBranch.equals(refBranch)) {
            return "fail";
        }

        if (deployEnableUserMap.get(userName) == null || !deployEnableUserMap.get(userName)) {
            LOGGER.info("unable auto deploy ：" + userName);
            return "fail";
        }
        reDeploy(projectName, userEmail);
        return "success";
    }

    private static String githubUserName(Map<String, Object> requestBodyMap) {
        try {
            return ((Map) requestBodyMap.get("pusher")).get("name").toString();
        } catch (Exception e) {
            LOGGER.error("error_githubUserName" + requestBodyMap, e);
        }
        return "";
    }

    private static String githubUserEmail(Map<String, Object> requestBodyMap) {
        try {
            return ((Map) requestBodyMap.get("pusher")).get("email").toString();
        } catch (Exception e) {
            LOGGER.error("error_githubUserEmail" + requestBodyMap, e);
        }
        return "";
    }

    private static String cacheKey(String projectName, String refBranch) {
        return projectName + ":" + refBranch;
    }

    private String getDeployShellPath(String projectName) {
        return deployShellPathTemplate.replace("{projectName}", projectName);
    }

    private String getDeployShellPathWithEmail(String projectName, String emailAddress) {
        return "/home/souche/sunshuhan/done_deploy_send_email.sh " + projectName + " " + emailAddress;
    }

    private void reDeploy(String projectName, String userEmail) {
        String deployPath = Optional.ofNullable(deployShellPathMap.get(projectName)).orElse(getDeployShellPathWithEmail(projectName, userEmail));
        try {
            Runtime.getRuntime().exec(deployPath);
            LOGGER.info(projectName + " execute deploy: " + deployPath);
        } catch (Exception e) {
            LOGGER.error("error_reDeploy : " + projectName, e);
        }
    }

}