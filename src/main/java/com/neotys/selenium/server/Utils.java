package com.neotys.selenium.server;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Utils {
    public static<T> List<T> merge(List<T> list1, List<T> list2)
    {
        return Stream.concat(list1.stream(), list2.stream())
                .collect(Collectors.toList());
    }
}
