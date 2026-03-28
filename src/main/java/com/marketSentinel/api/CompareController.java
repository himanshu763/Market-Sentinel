package com.marketSentinel.api;

import com.marketSentinel.model.CompareRequest;
import com.marketSentinel.model.CompareResult;
import com.marketSentinel.model.ProductIdentity;
import com.marketSentinel.parser.UrlParser;
import com.marketSentinel.service.CompareService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "http://localhost:5173")
public class CompareController {

    private final UrlParser urlParser;
    private final CompareService compareService;

    public CompareController(UrlParser urlParser, CompareService compareService) {
        this.urlParser = urlParser;
        this.compareService = compareService;
    }

    @PostMapping("/compare")
    public ResponseEntity<?> compare(@RequestBody CompareRequest request) {
        ProductIdentity identity = urlParser.parse(request.getUrl());
        CompareResult result = compareService.compare(identity);
        return ResponseEntity.ok(result);
    }
}