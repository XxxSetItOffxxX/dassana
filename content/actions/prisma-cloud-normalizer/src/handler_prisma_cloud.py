from enum import Enum
from json import loads
from typing import Dict, Any, List

from aws_lambda_powertools.utilities.parser import event_parser, parse
from aws_lambda_powertools.utilities.typing import LambdaContext
from pydantic import BaseModel

from dassana.common.aws_client import parse_arn
from dassana.common.models import NormalizedOutput


class PrismaResourceData(BaseModel):
    arn: str = None
    tagSets: Dict[str, str] = {}
    tags: List[Dict[str, str]] = []


class PrismaResource(BaseModel):
    rrn: str
    id: str
    cloudType: str
    accountId: str
    region: str
    regionId: str
    resourceType: str = None
    data: PrismaResourceData


class Policy(BaseModel):
    policyId: str = None
    policyType: str = None


class alertState(Enum):
    open = "ACTIVE"
    snoozed = "ACTIVE"
    resolved = "INACTIVE"
    dismissed = "INACTIVE"

class PrismaAlert(BaseModel):
    id: str = None
    status: str = None
    alertId: str = None
    alertStatus: str = None
    policy: Policy = None
    policyId: str = None
    policyType: str = None
    severity: str = None
    firstSeen: int = None
    message: Dict[Any, Any] = None
    history: List[Dict[Any, Any]] = None
    resource: PrismaResource = None
    tags: List[Dict[Any, Any]] = None


@event_parser(model=PrismaAlert)
def handle(event: PrismaAlert, context: LambdaContext):
    if event.message is not None:  # Splunk Event
        event = parse(event.message, model=PrismaAlert)
        alert_time, alert_status, vendor_severity = None, alertState[event.alertStatus].value, event.severity.lower()
    arn_obj = parse_arn(event.resource.data.arn) if event.resource.data.arn else None
    if (event.tags is not None) and len(event.tags) > 0:
        tags = list(map(lambda x: {
            "value": x.get('value'),
            "name": x.get('key')
        }, event.tags))
    else:
        tags = []

    if event.alertId and event.policyId:  # SQS
        alert_id, vendor_policy, vendor_severity, alert_time, alert_status = event.alertId, event.policyId, event.severity.lower(), event.firstSeen, alertState[event.alertStatus].value
    elif event.id and event.policy.policyId:  # Prisma
        alert_id, vendor_policy, vendor_severity, alert_time, alert_status = event.id, event.policy.policyId, None, event.firstSeen, alertState[event.status].value
    else:
        alert_id, vendor_policy, vendor_severity, alert_time, alert_status = None, None, None, None, None

    output = NormalizedOutput(
        vendorId='prisma-cloud',
        alertId=alert_id,
        canonicalId=event.resource.data.arn,
        vendorPolicy=vendor_policy,
        vendorSeverity=vendor_severity,
        csp=event.resource.cloudType,
        resourceContainer=event.resource.accountId,
        region=event.resource.regionId,
        alertTime=alert_time,
        alertState=alert_status,
        service=arn_obj.service if arn_obj is not None else None,
        resourceType=arn_obj.resource_type if arn_obj is not None else None,
        resourceId=arn_obj.resource if arn_obj is not None else event.resource.id,
        tags=tags
    )
    return loads(output.json())
