/*
 * Copyright 2026 Bitubi Solutions
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for
 * the specific language governing permissions and limitations under the License.
 */
package ao.bitubi.inference4j.server.api.openai;

import ao.bitubi.inference4j.sdk.Bi4jException;
import ao.bitubi.inference4j.sdk.ContextLengthExceededException;
import ao.bitubi.inference4j.sdk.SessionBusyException;
import ao.bitubi.inference4j.sdk.UnsupportedInputException;
import ao.bitubi.inference4j.server.ModelHost;
import ao.bitubi.inference4j.server.ModelNotReadyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Every error leaves as the OpenAI envelope {@code {"error":{message,type,param,code}}}, never a stack trace. */
@RestControllerAdvice(basePackageClasses = ChatCompletionsController.class)
public class OpenAiErrors {
    private static final Logger log = LoggerFactory.getLogger(OpenAiErrors.class);

    private static ResponseEntity<OpenAi.ErrorBody> error(HttpStatus status, String type, String code, String param, String message, HttpHeaders headers) {
        return new ResponseEntity<>(new OpenAi.ErrorBody(new OpenAi.ErrorDetail(message, type, param, code)), headers, status);
    }

    private static ResponseEntity<OpenAi.ErrorBody> error(HttpStatus status, String type, String code, String param, String message) {
        return error(status, type, code, param, message, new HttpHeaders());
    }

    @ExceptionHandler(SessionBusyException.class)
    ResponseEntity<OpenAi.ErrorBody> busy(SessionBusyException e) {
        return error(HttpStatus.CONFLICT, "invalid_request_error", "session_busy", null, e.getMessage());
    }

    @ExceptionHandler(ContextLengthExceededException.class)
    ResponseEntity<OpenAi.ErrorBody> context(ContextLengthExceededException e) {
        return error(HttpStatus.BAD_REQUEST, "invalid_request_error", "context_length_exceeded", "messages", e.getMessage());
    }

    @ExceptionHandler(ModelNotReadyException.class)
    ResponseEntity<OpenAi.ErrorBody> notReady(ModelNotReadyException e) {
        if (e.status().state() == ModelHost.State.FAILED) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "server_error", "model_failed", null, e.status().detail());
        }
        HttpHeaders h = new HttpHeaders();
        h.set(HttpHeaders.RETRY_AFTER, "5");
        return error(HttpStatus.SERVICE_UNAVAILABLE, "server_error", "model_loading", null, e.getMessage(), h);
    }

    @ExceptionHandler(ChatCompletionsController.InvalidSessionException.class)
    ResponseEntity<OpenAi.ErrorBody> invalidSession(ChatCompletionsController.InvalidSessionException e) {
        return error(HttpStatus.BAD_REQUEST, "invalid_request_error", "invalid_session", null, e.getMessage());
    }

    @ExceptionHandler({ IllegalArgumentException.class, HttpMessageNotReadableException.class })
    ResponseEntity<OpenAi.ErrorBody> invalid(Exception e) {
        String message = e instanceof HttpMessageNotReadableException ? "the request body is not valid JSON for this endpoint" : e.getMessage();
        return error(HttpStatus.BAD_REQUEST, "invalid_request_error", "invalid_request", null, message);
    }

    @ExceptionHandler(OpenAiMapper.UnsupportedRequestException.class)
    ResponseEntity<OpenAi.ErrorBody> unsupported(OpenAiMapper.UnsupportedRequestException e) {
        return error(HttpStatus.NOT_IMPLEMENTED, "invalid_request_error", e.code(), null, e.getMessage());
    }

    @ExceptionHandler(UnsupportedInputException.class)
    ResponseEntity<OpenAi.ErrorBody> unsupportedInput(UnsupportedInputException e) {
        return error(HttpStatus.NOT_IMPLEMENTED, "invalid_request_error", "unsupported_input", null, e.getMessage());
    }

    @ExceptionHandler(Bi4jException.class)
    ResponseEntity<OpenAi.ErrorBody> engine(Bi4jException e) {
        log.warn("engine error", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "server_error", "engine_error", null, e.getMessage());
    }

    /** Last resort, so that an unexpected failure still leaves in OpenAI's envelope and without a stack trace. */
    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<OpenAi.ErrorBody> unexpected(RuntimeException e) {
        log.error("unexpected error", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "server_error", "internal_error", null, "internal server error");
    }
}
