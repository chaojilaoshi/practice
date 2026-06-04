package com.practice.invoke.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 演示用「被调用方」接口，模拟另一个系统（service-b）。
 *
 * <p>仅用于本地自测：data.sql 里把 demo 接口的 base_url 配成本机地址，
 * 这样 /api/invoke 就能直连到这里，验证整条链路。生产环境删除即可。
 */
@RestController
@RequestMapping("/provider")
public class DemoProviderController {

    /** BODY 形态：接收 JSON 入参 */
    @PostMapping("/order")
    public Map<String, Object> createOrder(@RequestBody Map<String, Object> body) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("orderId", "ORD-" + System.currentTimeMillis());
        resp.put("received", body);
        return resp;
    }

    /** QUERY 形态：接收 query 参数 */
    @GetMapping("/user")
    public Map<String, Object> getUser(@RequestParam("id") String id) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("id", id);
        resp.put("name", "user-" + id);
        return resp;
    }

    /** PATH 形态：接收路径变量 */
    @GetMapping("/product/{code}")
    public Map<String, Object> getProduct(@PathVariable("code") String code) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("code", code);
        resp.put("title", "product-" + code);
        return resp;
    }
}
