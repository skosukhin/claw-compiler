PROGRAM loop_fusion

 CALL clawloop ( )
END PROGRAM loop_fusion

SUBROUTINE clawloop ( )
 INTEGER :: i
 INTEGER :: j
 INTEGER :: k

!$claw loop-fusion collapse(2)
 DO i = 1 , 5 , 1
  DO j = 1 , 4 , 1
   DO k = 1 , 2 , 1
    PRINT * ,"First loop body:" , i , j
   END DO
   DO k = 1 , 2 , 1
    PRINT * ,"Second loop body:" , i , j
   END DO
  END DO
 END DO
END SUBROUTINE clawloop
