package com.test;

import org.springframework.stereotype.Repository;

@Repository
public class TestRepository {

    public String findData() {
        return "data";
    }
}