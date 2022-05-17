package com.mercurius;

import jakarta.annotation.PostConstruct;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Named;

import java.util.ArrayList;
import java.util.List;

@Named
@ViewScoped





public class IndexBean {
    public record person(String name){}
private List<person> personsList;

    @PostConstruct
    public void init(){
        personsList = new ArrayList<>();
    }

    public void add(){
        person p = null;
        personsList.add(p);
    }

}
