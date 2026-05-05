package io.devportal.runtime.verify;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class VerifyController {

    private final VerifyService verify;

    public VerifyController(VerifyService verify) { this.verify = verify; }

    @PostMapping("/api/assets/{id}/verify")
    public VerifyResult verify(
        @PathVariable String id,
        @RequestParam(required = false, defaultValue = "docker") String stage
    ) {
        return verify.verify(id, stage);
    }
}
