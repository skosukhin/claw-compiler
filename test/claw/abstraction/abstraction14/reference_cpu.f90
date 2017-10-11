MODULE mo_column

CONTAINS
 FUNCTION compute_column ( nz , b , q , t , nproma ) RESULT(r)
  INTEGER , INTENT(IN) :: nz
  INTEGER , INTENT(IN) :: b
  REAL , INTENT(INOUT) :: t ( 1 : nproma , 1 : b )
  REAL , INTENT(INOUT) :: q ( 1 : b , 1 : nproma , 1 : 2 )
  INTEGER , INTENT(IN) :: nproma
  INTEGER :: k
  REAL :: c
  INTEGER :: r
  INTEGER :: proma

  c = 5.345
  DO k = 2 , nz , 1
   DO proma = 1 , nproma , 1
    t ( proma , k ) = c * k
   END DO
   DO proma = 1 , nproma , 1
    q ( k , proma , 1 ) = q ( k - 1 , proma , 1 ) + t ( proma , k ) * c
   END DO
  END DO
  DO proma = 1 , nproma , 1
   q ( nz , proma , 1 ) = q ( nz , proma , 1 ) * c
  END DO
 END FUNCTION compute_column

 SUBROUTINE compute ( nz , b , q , t , nproma )

  INTEGER , INTENT(IN) :: nz
  INTEGER , INTENT(IN) :: b
  REAL , INTENT(INOUT) :: t ( 1 : nproma , 1 : b )
  REAL , INTENT(INOUT) :: q ( 1 : b , 1 : nproma , 1 : 2 )
  INTEGER , INTENT(IN) :: nproma
  INTEGER :: result

  result = compute_column ( nz , b , q , t , nproma = nproma )
 END SUBROUTINE compute

END MODULE mo_column

