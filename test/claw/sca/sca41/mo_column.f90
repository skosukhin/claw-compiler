!
! This file is released under terms of BSD license
! See LICENSE file for more information
!

MODULE mo_column
  IMPLICIT NONE
CONTAINS
  ! Compute single point with elemental function
  ELEMENTAL FUNCTION compute_point(t) RESULT(q)
    IMPLICIT NONE

    !$claw model-data
    REAL, INTENT(IN)   :: t ! Field declared as one column only
    REAL :: q ! Field declared as one column only
    !$claw end model-data

    REAL :: c

    !$claw sca

    c = 5.345
    q = q + t * c
  END FUNCTION compute_point
END MODULE mo_column
