/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.http.MediaType.TEXT_PLAIN_VALUE;

@Slf4j
@RestController
@AllArgsConstructor
@SuppressWarnings("unused")
public class DownloadLogController {

    @GetMapping(
            path = "/list-logfiles",
            produces = {APPLICATION_JSON_VALUE}
    )
    ResponseEntity<Set<String>> getLogFileNames() {
        File workingDir = new File(".");
        File[] logFiles = workingDir
                .listFiles(file -> file.getName().endsWith(".log"));

        Set<String> fileNames = new TreeSet<>();
        if (logFiles != null) {
            fileNames.addAll(Arrays.stream(logFiles)
                    .map(File::getName)
                    .collect(Collectors.toSet()));
        }
        return ResponseEntity.ok(fileNames);
    }

    @GetMapping(
            path = "/download-logfile",
            produces = {TEXT_PLAIN_VALUE}
    )
    ResponseEntity<Resource> downloadLogFile(
            @RequestParam(value = "filename", defaultValue = "aws-exporter.log") String logFileName) {
        File logFile = new File(logFileName);
        try {
            InputStreamResource file = new InputStreamResource(new GZIPInputStream(new FileInputStream(logFile)));
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + logFileName)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(file);
        } catch (IOException e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }
}
