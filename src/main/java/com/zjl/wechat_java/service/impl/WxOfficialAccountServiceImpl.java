package com.zjl.wechat_java.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.zjl.wechat_java.config.WxOaUrlConfig;
import com.zjl.wechat_java.config.WxOfficialsAccountConfiguration;
import com.zjl.wechat_java.domain.vo.OaUserVO;
import com.zjl.wechat_java.entity.OaUser;
import com.zjl.wechat_java.error.WeChatOaErrorEnum;
import com.zjl.wechat_java.exception.WeChatOaException;
import com.zjl.wechat_java.exception.WxErrorException;
import com.zjl.wechat_java.mapper.OaUserRepository;
import com.zjl.wechat_java.service.WxOfficialAccountService;
import com.zjl.wechat_java.util.HttpUtil;
import com.zjl.wechat_java.util.RedisUtil;
import com.zjl.wechat_java.util.WebResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * @className: WxOfficialAccountServiceImpl
 * @author: zhou
 * @description: 微信公众号实现类
 * @datetime: 2019/6/7 19:04
 */
@Slf4j
@Service
public class WxOfficialAccountServiceImpl implements WxOfficialAccountService{
    @Autowired
    private RedisUtil redisUtil;
    @Autowired
    private HttpUtil httpUtil;
    @Autowired
    private WxOfficialsAccountConfiguration wxOfficialsAccountConfiguration;
    @Autowired
    private OaUserRepository oaUserRepository;

    /**
     * @description: 获取公众号的accessToken
     * @author: zhou
     * @param:
     * @return:
     * @date: 2019/6/7
     */
    @Override
    public String refreshAccessToken() {
        //拼装请求地址
        StringBuilder sb = new StringBuilder(WxOaUrlConfig.API_ACCESS_TOKEN);
        sb.append("&")
          .append("appid=").append(wxOfficialsAccountConfiguration.getAppid())
          .append("&").append("secret=").append(wxOfficialsAccountConfiguration.getAppsecret());
        String url = sb.toString();
        //请求微信api
        try {
            JSONObject jsonObject = httpUtil.doGetJson(url);
            if(jsonObject.containsKey("access_token")){
                return jsonObject.get("access_token").toString();
            }else {
                log.error(jsonObject.toJSONString());
                throw new WxErrorException(jsonObject.getInteger("errcode"),
                        jsonObject.get("errmsg").toString());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * @description 获取网页授权的accessToken
     * @author zhou
     * @param code 预授权码
     * @return jsonObject
     * @date 2019/6/8
     */
    @Override
    public JSONObject getWebAccessToken(String code) {
        //拼装url
        StringBuilder sb = new StringBuilder(WxOaUrlConfig.API_WEB_ACCESS_TOKEN);
        sb.append("appid=").append(wxOfficialsAccountConfiguration.getAppid())
           .append("&").append("secret=").append(wxOfficialsAccountConfiguration.getAppsecret())
           .append("&").append("code=").append(code)
           .append("&grant_type=authorization_code");
        String url = sb.toString();
        JSONObject jsonObject = null;
        try {
            jsonObject = httpUtil.doGetJson(url);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return jsonObject;
    }

    /**
     * @description 登录
     * @author zhou
     * @param tokenObject 网页授权token信息
     * @return webResponse
     * @date 2019/6/9
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public WebResponse loginInfo(JSONObject tokenObject) {
        //网页授权token存入redis
        redisUtil.set(tokenObject.getString("openid"),tokenObject.getString("access_token"),
                (long) 7200);
        //拼装请求url
        StringBuilder sb = new StringBuilder(WxOaUrlConfig.API_USER_INFO);
        sb.append("access_token=").append(tokenObject.getString("access_token"))
           .append("&").append("openid=").append(tokenObject.getString("openid"))
           .append("&").append("lang=zh_CN");
        String url = sb.toString();
        OaUserVO oaUserVO = new OaUserVO();
        try {
            //获取用户信息
            JSONObject jsonResult = httpUtil.doGetJson(url);
            if(jsonResult.containsKey("errmsg")){
                //含有错误信息
                log.error(jsonResult.getString("errmsg"));
                throw new WxErrorException(jsonResult.getInteger("errcode"),
                        jsonResult.getString("errmsg"));
            }else{
                OaUser oaUser = OaUser.builder()
                        .avatar(jsonResult.getString("headimgurl"))
                        .city(jsonResult.getString("city"))
                        .country(jsonResult.getString("country"))
                        .createTime(LocalDateTime.now())
                        .deleted(false)
                        .nickName(jsonResult.getString("nickname"))
                        .openid(jsonResult.getString("openid"))
                        .province(jsonResult.getString("province"))
                        .refreshTime(LocalDateTime.now())
                        .refreshToken(tokenObject.getString("refresh_token"))
                        .sex(jsonResult.getShort("sex"))
                        .unionid(jsonResult.containsKey("unionid")?jsonResult.getString("unionid"):null)
                        .privilege(jsonResult.getString("privilege"))
                        .updateTime(LocalDateTime.now())
                        .build();
                oaUserRepository.saveAndFlush(oaUser);
                oaUserVO.setAvatar(jsonResult.getString("headimgurl"));
                oaUserVO.setNickname(jsonResult.getString("nickname"));
                oaUserVO.setOpenid(jsonResult.getString("openid"));
                oaUserVO.setUserId(oaUser.getUserId());
            }
        } catch (Exception e) {
            log.error("登录失败");
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            throw new WeChatOaException(WeChatOaErrorEnum.AUTH_LOGIN_ERROR);
        }
        return WebResponse.success(oaUserVO);
    }
}
