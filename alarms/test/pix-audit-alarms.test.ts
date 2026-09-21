import * as fs from 'fs';
import * as path from 'path';
import { App } from 'aws-cdk-lib';
import { Template, Match } from 'aws-cdk-lib/assertions';
import { PixAuditAlarmsStack, AUDIT_ALARM_TOKENS } from '../lib/pix-audit-alarms';

/**
 * Assertions on the synthesised template, not on the source that produced it.
 *
 * A `cdk synth` that merely succeeds proves the TypeScript compiles - it says nothing about whether
 * the alarms are correct. Replacing unverified documentation with unverified infrastructure code
 * would not be progress, so what follows checks the emitted CloudFormation.
 */
function synth(): Template {
  const app = new App();
  const stack = new PixAuditAlarmsStack(app, 'TestStack', {
    logGroupName: '/pix/test-proxy',
    deliveryStreamName: 'pix-test-audit-stream',
  });
  return Template.fromStack(stack);
}

describe('Pix audit alarms', () => {
  test('every audit alarm token gets a metric filter AND an alarm', () => {
    const template = synth();

    template.resourceCountIs('AWS::Logs::MetricFilter', AUDIT_ALARM_TOKENS.length);

    for (const token of AUDIT_ALARM_TOKENS) {
      template.hasResourceProperties('AWS::Logs::MetricFilter', {
        FilterPattern: `"${token}"`,
        MetricTransformations: Match.arrayWith([
          Match.objectLike({ MetricName: token, MetricValue: '1' }),
        ]),
      });
      template.hasResourceProperties('AWS::CloudWatch::Alarm', {
        AlarmName: token,
      });
    }
  });

  /**
   * The defect this test exists for: the tokens live in Java
   * (com.amazon.aws.pix.core.audit.AuditAlarmTokens) and are duplicated here. A rename on the Java
   * side would leave a metric filter matching a string nothing emits any more - an alarm that can
   * never fire, which is strictly worse than no alarm, because a silent filter looks like coverage.
   *
   * So this reads the Java source and compares. It asserts on the VALUES the Java constants hold,
   * not on the constant names, because renaming the constant while keeping its value is harmless and
   * renaming the value is what breaks the alarm.
   */
  test('the token list agrees with the Java source of truth', () => {
    const javaFile = path.resolve(
      __dirname,
      '../../proxy/core/src/main/java/com/amazon/aws/pix/core/audit/AuditAlarmTokens.java',
    );
    expect(fs.existsSync(javaFile)).toBe(true);

    const java = fs.readFileSync(javaFile, 'utf8');
    const javaTokens = [...java.matchAll(/"(PIX_AUDIT_[A-Z_]+)"/g)].map((m) => m[1]).sort();

    expect(javaTokens.length).toBeGreaterThan(0);
    expect(javaTokens).toEqual([...AUDIT_ALARM_TOKENS].sort());
  });

  /**
   * treatMissingData is the whole point of the Firehose alarms, so it is asserted explicitly.
   * "No data" for a stream that should always carry audit records IS the outage - the container
   * died, delivery stopped, or the stream was deleted. NotBreaching there turns a total audit
   * failure into a green dashboard.
   */
  test('Firehose alarms treat missing data as BREACHING', () => {
    const template = synth();

    for (const name of ['PixAuditDeliveryToS3Failed', 'PixAuditIncomingRecordsStopped']) {
      template.hasResourceProperties('AWS::CloudWatch::Alarm', {
        AlarmName: name,
        TreatMissingData: 'breaching',
        Namespace: 'AWS/Firehose',
        Dimensions: Match.arrayWith([
          Match.objectLike({ Name: 'DeliveryStreamName', Value: 'pix-test-audit-stream' }),
        ]),
      });
    }
  });

  /**
   * Negative control for the test above. The metric-filter alarms must take the OPPOSITE setting:
   * those metrics only emit data points when something has gone wrong, so absence really is health
   * and BREACHING would page continuously on a healthy system. If both groups had the same value,
   * the assertion above would pass for a stack that had simply defaulted everything.
   */
  test('metric-filter alarms treat missing data as notBreaching', () => {
    const template = synth();

    for (const token of AUDIT_ALARM_TOKENS) {
      template.hasResourceProperties('AWS::CloudWatch::Alarm', {
        AlarmName: token,
        TreatMissingData: 'notBreaching',
      });
    }
  });

  test('the record-stopped alarm fires on too FEW records, not too many', () => {
    const template = synth();

    template.hasResourceProperties('AWS::CloudWatch::Alarm', {
      AlarmName: 'PixAuditIncomingRecordsStopped',
      ComparisonOperator: 'LessThanThreshold',
      MetricName: 'IncomingRecords',
    });
  });

  test('every alarm carries a description pointing somewhere actionable', () => {
    const template = synth();
    const alarms = template.findResources('AWS::CloudWatch::Alarm');

    expect(Object.keys(alarms).length).toBe(AUDIT_ALARM_TOKENS.length + 2);
    for (const [logicalId, resource] of Object.entries(alarms)) {
      const description = (resource as { Properties: { AlarmDescription?: string } }).Properties
        .AlarmDescription;
      expect(typeof description).toBe('string');
      expect((description as string).length).toBeGreaterThan(40);
      void logicalId;
    }
  });
});
