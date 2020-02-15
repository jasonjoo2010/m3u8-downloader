package com.yoloho.training;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;

import com.google.common.io.CharStreams;
import com.google.common.io.LineProcessor;

public class Main {
    public static void main(String[] args) {
        if (args.length < 3) {
            System.out.println("命令语法：java -jar m3u8-downloader.jar <m3u8文件url>");
            System.out.println("Usage：java -jar m3u8-downloader.jar <m3u8_url>");
            System.out.println();
            System.exit(1);
        }
        File url = new File(args[0]);
    }
}
