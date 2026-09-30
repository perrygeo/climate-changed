########################################
# ZeroFS encryption password (SSM)
#
# ZeroFS encrypts everything it writes to the data bucket. The encryption
# password must survive instance rebuilds (that's the whole point of moving
# era_ts to S3), so it lives in SSM Parameter Store rather than on the
# instance's ephemeral disk. The NixOS config fetches it at boot using the
# instance profile (see infra/nixos/configuration.nix).
########################################

resource "random_password" "zerofs" {
  length  = 32
  special = false
}

resource "aws_ssm_parameter" "zerofs_password" {
  name  = "/climate-changed/zerofs/encryption-password"
  type  = "SecureString"
  value = random_password.zerofs.result

  tags = {
    Name = "climate-changed-zerofs-password"
  }
}

data "aws_iam_policy_document" "ssm_get_parameter" {
  statement {
    actions   = ["ssm:GetParameter"]
    resources = [aws_ssm_parameter.zerofs_password.arn]
  }
}

resource "aws_iam_role_policy" "ssm_get_parameter" {
  name   = "climate-changed-zerofs-password"
  role   = aws_iam_role.app.id
  policy = data.aws_iam_policy_document.ssm_get_parameter.json
}
