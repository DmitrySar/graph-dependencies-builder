package com.test;

import org.springframework.stereotype.Service;

@Service
public class TestService {

    public String greet(String name) {
        return "Hello, " + name + "!";
    }
}