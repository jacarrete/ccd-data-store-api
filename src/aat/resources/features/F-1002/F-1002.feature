@F-1002
Feature: F-1002: Update Case Data Content

  Background: Load test data for the scenario
    Given an appropriate test context as detailed in the test data source

  @S-1011
  Scenario: must successfully update case data content with new Document Id
    Given a user with [an active caseworker profile in CCD with full permissions on a document field],
    And a successful call [by same user to upload a document with mandatory metadata] as in [Default_Document_Upload],
    And another successful call [by same user to create a token for case creation] as in [Befta_Jurisdiction2_Default_Token_Creation_Data_For_Case_Creation]
    And another successful call [by same user to create a case of this case type] as in [Befta_Jurisdiction2_Default_Full_Case_Creation_Data]
    When a request is prepared with appropriate values,
    And the request [contains an Event Id received from upstream],
    And the request [contains a Case Id and Document Id created above],
    And the request [contains a Content-Type header with V2.1],
    And it is submitted to call the [Update Case Data Content] operation of [CCD Data Store],
    Then a positive response is received,
    And the response has all other details as expected.

  @S-1012
  Scenario: must successfully update case data content without any document field
    Given a user with [an active caseworker profile in CCD with full permissions on a document field],
    And another successful call [by same user to create a token for case creation] as in [Befta_Jurisdiction2_Default_Token_Creation_Data_For_Case_Creation]
    And another successful call [by same user to create a case of this case type] as in [Befta_Jurisdiction2_Default_Full_Case_Creation_Data]
    When a request is prepared with appropriate values,
    And the request [contains an Event Id received from upstream],
    And the request [contains a Case Id along with metadata created above],
    And the request [contains a Content-Type header with V2.1],
    And it is submitted to call the [Update Case Data Content] operation of [CCD Data Store],
    Then a positive response is received,
    And the response has all other details as expected.

  @S-1013
  Scenario: must successfully update case data content for multiple documents
    Given a user with [an active caseworker profile in CCD with full permissions on a document field],
    And a successful call [by same user to upload a document with mandatory metadata] as in [Default_Document_Upload_1],
    And a successful call [by same user to upload a document with mandatory metadata] as in [Default_Document_Upload_2],
    And a successful call [by same user to upload a document with mandatory metadata] as in [Default_Document_Upload_3],
    And another successful call [by same user to create a token for case creation] as in [Befta_Jurisdiction2_Default_Token_Creation_Data_For_Case_Creation],
    And another successful call [by same user to create a case of this case type] as in [Befta_Jurisdiction2_Default_Full_Case_Creation_Data],
    When a request is prepared with appropriate values,
    And the request [contains the Case Id along with ids of the documents uploaded above],
    And the request [contains a Content-Type header with V2.1],
    And it is submitted to call the [Update Case Data Content] operation of [CCD Data Store],
    Then a positive response is received,
    And the response has all other details as expected.

  @S-1014
  Scenario: must get an error response for a wrong hash token and revert the whole transaction
    Given a user with [an active caseworker profile in CCD with full permissions on a document field],
    And a successful call [by same user to upload a document with mandatory metadata] as in [Default_Document_Upload],
    And another successful call [by same user to create a token for case creation] as in [Befta_Jurisdiction2_Default_Token_Creation_Data_For_Case_Creation]
    And another successful call [by same user to create a case of this case type] as in [Befta_Jurisdiction2_Default_Full_Case_Creation_Data]
    When a request is prepared with appropriate values,
    And the request [contains an update for Case Metadata],
    And the request [contains a Document Id just created above],
    And the request [contains a wrong hash token],
    And the request [contains a Content-Type header with V2.1],
    And it is submitted to call the [Update Case Data Content] operation of [CCD Data Store],
    Then a negative response is received,
    And the response has all the details as expected.

  @S-1015
  Scenario: must get an error response for a non existing document Id
    Given a user with [an active caseworker profile in CCD with full permissions on a document field],
    And a successful call [by same user to upload a document with mandatory metadata] as in [Default_Document_Upload],
    And another successful call [by same user to create a token for case creation] as in [Befta_Jurisdiction2_Default_Token_Creation_Data_For_Case_Creation],
    And another successful call [by same user to create a case of this case type] as in [Befta_Jurisdiction2_Default_Full_Case_Creation_Data],
    When a request is prepared with appropriate values,
    And the request [contains a non existing document Id],
    And the request [contains a Content-Type header with V2.1],
    And it is submitted to call the [Update Case Data Content] operation of [CCD Data Store],
    Then a negative response is received,
    And the response has all the details as expected.

  @S-1016

  Scenario: must get an error response for a malformed document Id
    Given a user with [an active caseworker profile in CCD with full permissions on a document field],
    And a successful call [by same user to upload a document with mandatory metadata] as in [Default_Document_Upload],
    And another successful call [by same user to create a token for case creation] as in [Befta_Jurisdiction2_Default_Token_Creation_Data_For_Case_Creation],
    And another successful call [by same user to create a case of this case type] as in [Befta_Jurisdiction2_Default_Full_Case_Creation_Data],
    When a request is prepared with appropriate values,
    And the request [contains a malformed document Id],
    And the request [contains a Content-Type header with V2.1],
    And it is submitted to call the [Update Case Data Content] operation of [CCD Data Store],
    Then a negative response is received,
    And the response has all the details as expected.

  @S-1017

  Scenario: must get an error response for a non existing Case Id
    Given a user with [an active caseworker profile in CCD with full permissions on a document field],
    And another successful call [by same user to create a token for case creation] as in [Befta_Jurisdiction2_Default_Token_Creation_Data_For_Case_Creation],
    And another successful call [by same user to create a case of this case type] as in [Befta_Jurisdiction2_Default_Full_Case_Creation_Data],
    When a request is prepared with appropriate values,
    And the request [contains a non existing Case Id],
    And the request [contains a Content-Type header with V2.1],
    And it is submitted to call the [Update Case Data Content] operation of [CCD Data Store],
    Then a negative response is received,
    And the response has all the details as expected.

  @S-1018

  Scenario: must get an error response for a malformed Case Id
    Given a user with [an active caseworker profile in CCD with full permissions on a document field],
    And another successful call [by same user to create a token for case creation] as in [Befta_Jurisdiction2_Default_Token_Creation_Data_For_Case_Creation],
    And another successful call [by same user to create a case of this case type] as in [Befta_Jurisdiction2_Default_Full_Case_Creation_Data],
    When a request is prepared with appropriate values,
    And the request [contains a malformed Case Id],
    And the request [contains a Content-Type header with V2.1],
    And it is submitted to call the [Update Case Data Content] operation of [CCD Data Store],
    Then a negative response is received,
    And the response has all the details as expected.

  @S-1019
  Scenario: generic scenario for Unauthorised

  @S-1020
  Scenario: generic scenario for Forbidden
