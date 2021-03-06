import time

from entityservice.tests.config import url
from entityservice.tests.util import generate_overlapping_clk_data, get_project_description, delete_project


def _check_new_project_response_fields(new_project_data):
    assert 'project_id' in new_project_data
    assert 'update_tokens' in new_project_data
    assert 'result_token' in new_project_data
    assert len(new_project_data['update_tokens']) == 2


def test_simple_create_project(requests):
    project_name = 'a test project'
    project_description = 'created by unittest'

    project_response = requests.post(url + '/projects', json={
        'schema': {},
        'result_type': 'mapping',
        'number_parties': 2,
        'name': project_name,
        'notes': project_description
    })

    assert project_response.status_code == 201
    new_project_data = project_response.json()
    _check_new_project_response_fields(new_project_data)


def test_create_then_delete_no_auth(requests):
    new_project_response = requests.post(url + '/projects', json={
        'schema': {},
        'result_type': 'mapping',
    })
    assert new_project_response.status_code == 201
    _check_new_project_response_fields(new_project_response.json())

    delete_project_response = requests.delete(url + '/projects/{}'.format(new_project_response.json()['project_id']))
    assert 400 == delete_project_response.status_code


def test_create_then_delete_invalid_auth(requests):
    new_project_response = requests.post(url + '/projects', json={
        'schema': {},
        'result_type': 'mapping',
    })
    assert new_project_response.status_code == 201
    _check_new_project_response_fields(new_project_response.json())

    delete_project_response = requests.delete(
        url + '/projects/{}'.format(new_project_response.json()['project_id']),
        headers={'Authorization': 'invalid'}
    )
    assert delete_project_response.status_code == 403


def test_create_then_delete_valid_auth(requests):
    new_project_response = requests.post(url + '/projects', json={
        'schema': {},
        'result_type': 'mapping',
    })
    assert new_project_response.status_code == 201
    _check_new_project_response_fields(new_project_response.json())

    token = new_project_response.json()['result_token']
    delete_project_response = requests.delete(
        url + '/projects/{}'.format(new_project_response.json()['project_id']),
        headers={'Authorization': token}
    )
    assert delete_project_response.status_code == 204


def test_delete_project_types(requests, project):
    delete_project(requests, project)


def test_create_then_list(requests):
    original_project_list_respose = requests.get(url + '/projects').json()
    original_project_ids = [p['project_id'] for p in original_project_list_respose]

    new_project_response = requests.post(url + '/projects', json={
        'schema': {},
        'result_type': 'mapping',
    }).json()

    assert new_project_response['project_id'] not in original_project_ids

    project_list_response = requests.get(url + '/projects').json()
    new_project_ids = [p['project_id'] for p in project_list_response]

    assert new_project_response['project_id'] in new_project_ids


def test_create_then_describe_noauth(requests):
    new_project = requests.post(url + '/projects', json={
        'schema': {},
        'result_type': 'mapping',
    }).json()

    r = requests.get(url + '/projects/{}'.format(new_project['project_id']))
    assert 400 == r.status_code


def test_create_then_describe_invalid_auth(requests):
    project_respose = requests.post(url + '/projects', json={
        'schema': {},
        'result_type': 'mapping',
    }).json()
    r = requests.get(
        url + '/projects/{}'.format(project_respose['project_id']),
        headers={'Authorization': 'invalid'}
    )
    assert r.status_code == 403


def test_create_then_describe_valid_auth(requests):
    project_respose = requests.post(url + '/projects', json={
        'schema': {},
        'result_type': 'mapping',
    }).json()
    r = requests.get(
        url + '/projects/{}'.format(project_respose['project_id']),
        headers={'Authorization': project_respose['result_token']}
    )
    assert r.status_code == 200
    project_description = r.json()

    assert 'project_id' in project_description
    assert 'name' in project_description
    assert 'notes' in project_description
    assert 'schema' in project_description
    assert 'error' in project_description
    assert not project_description['error']
    assert 'public_key' not in project_description
    assert 'paillier_context' not in project_description

    assert 'mapping' == project_description['result_type']
    assert 2 == project_description['number_parties'], 'default number of parties should be 2'
    assert '' == project_description['name'], 'default name should be blank'
    assert '' == project_description['notes'], 'default notes should be blank'


def test_describe_missing_project_with_invalidauth(requests):
    r = requests.get(
        url + '/projects/{}'.format('fakeprojectid'),
        headers={'Authorization': 'invalid'}
    )
    assert r.status_code == 403


def test_list_runs_of_missing_project_with_invalidauth(requests):
    r = requests.get(
        url + '/projects/{}/runs'.format('fakeprojectid'),
        headers={'Authorization': 'invalid'}
    )
    assert r.status_code == 403


def test_mapping_2_party_data_uploaded(requests):
    new_project_data = requests.post(url + '/projects',
                                     headers={'Authorization': 'invalid'},
                                     json={
                                         'schema': {},
                                         'result_type': 'mapping',
                                     }).json()
    description_1 = get_project_description(requests, new_project_data)
    assert description_1['number_parties'] == 2
    assert description_1['parties_contributed'] == 0

    d1, d2 = generate_overlapping_clk_data([100, 100], overlap=0.75)
    r1 = requests.post(
        url + '/projects/{}/clks'.format(new_project_data['project_id']),
        headers={'Authorization': new_project_data['update_tokens'][0]},
        json={
            'clks': d1
        }
    )
    time.sleep(0.5)
    description_2 = get_project_description(requests, new_project_data)
    assert description_2['number_parties'] == 2
    assert description_2['parties_contributed'] == 1

    r2 = requests.post(
        url + '/projects/{}/clks'.format(new_project_data['project_id']),
        headers={'Authorization': new_project_data['update_tokens'][1]},
        json={
            'clks': d2
        }
    )

    assert r1.status_code == 201
    assert r2.status_code == 201

    time.sleep(0.5)

    description_3 = get_project_description(requests, new_project_data)
    assert description_3['number_parties'] == 2
    assert description_3['parties_contributed'] == 2

