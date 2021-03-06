import pytest
from entityservice.tests.config import url
from entityservice.tests.util import create_project_upload_fake_data, post_run, get_run


def test_run_description_missing_run(requests, project):
    _ = get_run(requests, project, 'invalid', expected_status = 403)


def test_run_description_no_data(requests, project):
    run_id = post_run(requests, project, 0.95)
    run = get_run(requests, project, run_id)

    assert 'run_id' in run
    assert 'notes' in run
    assert 'threshold' in run


def test_run_description(requests, result_type):
    project, dp1, dp2 = create_project_upload_fake_data(requests, [100, 100], overlap=0.5, result_type=result_type)
    run_id = post_run(requests, project, 0.98)
    run = get_run(requests, project, run_id)

    assert 'run_id' in run
    assert 'notes' in run
    assert 'threshold' in run
