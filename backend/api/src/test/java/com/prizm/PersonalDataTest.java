package com.prizm;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.google.api.core.*;
import com.google.cloud.firestore.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class PersonalDataTest {
  @Test
  void firestoreStatusAndStageAreVisibleWithoutLeakingProviderDescription() {
    var cause =
        io.grpc.Status.PERMISSION_DENIED
            .withDescription("private credential detail")
            .asRuntimeException();
    var error =
        assertThrows(
            ResponseStatusException.class,
            () -> PersonalData.await(ApiFutures.immediateFailedFuture(cause), "채팅 문맥 저장"));
    assertEquals(503, error.getStatusCode().value());
    assertTrue(error.getReason().contains("채팅 문맥 저장"));
    assertTrue(error.getReason().contains("권한"));
    assertFalse(error.getReason().contains("private credential detail"));
  }

  @Test
  void applicationStatusInsideTransactionRemainsUnchanged() {
    var cause = new ResponseStatusException(HttpStatus.CONFLICT, "계정 삭제 중");
    assertSame(
        cause,
        assertThrows(
            ResponseStatusException.class,
            () -> PersonalData.await(ApiFutures.immediateFailedFuture(cause), "채팅 문맥 저장")));
  }

  @SuppressWarnings("unchecked")
  @Test
  void timeoutCancelsFutureAndExplainsTheStage() throws Exception {
    ApiFuture<Object> future = mock(ApiFuture.class);
    when(future.get(8, TimeUnit.SECONDS)).thenThrow(new TimeoutException());
    var error =
        assertThrows(
            ResponseStatusException.class, () -> PersonalData.await(future, "일별 가격 이력 읽기"));
    assertTrue(error.getReason().contains("시간이 초과"));
    verify(future).cancel(true);
  }

  @Test
  void firstTimeUsersWithoutProfileAreNotTreatedAsDeleting() throws Exception {
    var tx = mock(Transaction.class);
    var root = mock(DocumentReference.class);
    var doc = mock(DocumentSnapshot.class);
    when(tx.get(root)).thenReturn(ApiFutures.immediateFuture(doc));
    when(doc.exists()).thenReturn(false);
    assertDoesNotThrow(() -> PersonalData.active(tx, root));
    verify(doc, never()).getString(anyString());
  }
}
